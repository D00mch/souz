package ru.souz.backend.e2e

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.souz.backend.attachments.ATTACHMENT_EXECUTION_SAVE_LIMIT
import ru.souz.backend.attachments.ATTACHMENT_OWNER_SAVE_LIMIT
import ru.souz.backend.attachments.ATTACHMENT_PENDING_LIFETIME
import ru.souz.backend.attachments.AttachmentClock
import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.attachments.AttachmentSaveBudget
import ru.souz.backend.attachments.AttachmentStorage
import ru.souz.backend.attachments.AttachmentStorageConfig
import ru.souz.backend.attachments.assertAttachmentError
import ru.souz.backend.crypto.sha256Hex
import software.amazon.awssdk.services.s3.model.S3Exception

class BackendAttachmentSaveBudgetE2eTest {
    @Test
    fun `replicas share save limits and execution totals survive owner ledger pruning`() {
        val config = AttachmentMinio.config(AttachmentSaveBudget(2, 5, 4, 10))
        backendE2eTest("attachment_budget_limits", attachments = config) {
            val owner = UUID.randomUUID().toString()
            val (first, second, third, fourth) = executionsFor(owner, 4)
            val storage = backend.attachmentStorage
            val legacyKey = "$first:output:${"legacy".sha256Hex()}:${"legacy.txt".sha256Hex()}"
            val legacy = storage.save(owner, legacyKey, "legacy.txt", "legacy".byteInputStream())
            assertAttachmentError("attachment_execution_required") {
                storage.saveFile(owner, UUID.randomUUID(), "orphan.txt", "12345")
            }

            withPeerBackend { peer ->
                val original = storage.saveFile(owner, first, "report.txt", "123")
                assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) { storage.saveFile(owner, first, "large.txt", "456") }
                assertBudgetRace(peer, owner, ATTACHMENT_EXECUTION_SAVE_LIMIT) { replica ->
                    saveFile(owner, first, listOf("report.txt", "copy.txt")[replica], "45")
                }
                assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) { storage.saveFile(owner, first, "empty.txt", "") }
                assertEquals(legacy, storage.saveFile(owner, first, "legacy.txt", "legacy"))

                storage.saveFile(owner, second, "full.txt", "12345")
                assertAttachmentError(ATTACHMENT_OWNER_SAVE_LIMIT) { storage.saveFile(owner, third, "extra.txt", "1") }
                assertBudgetRace(peer, owner, ATTACHMENT_OWNER_SAVE_LIMIT) { replica ->
                    saveFile(owner, listOf(third, fourth)[replica], "empty.txt", "")
                }
                assertEquals(original, peer.backend.attachmentStorage.saveFile(owner, first, "report.txt", "123"))

                val other = UUID.randomUUID().toString()
                storage.saveFile(other, executionsFor(other).single(), "independent.txt", "1")
                val objects = AttachmentMinio.server.execInContainer("mc", "ls", "--recursive", "root/${config.bucket}")
                assertEquals(0, objects.exitCode, objects.stderr)
                assertEquals(6, objects.stdout.lineSequence().count { it.isNotBlank() })
                val metrics = listOf(client, peer.client).map { it.get("/metrics").bodyAsText() }
                fun total(series: String): Double {
                    val sample = Regex("(?m)^$series ([0-9.]+)$")
                    return metrics.sumOf { sample.find(it)?.groupValues?.get(1)?.toDouble() ?: 0.0 }
                }
                assertEquals(11.0, total("souz_attachment_save_admitted_bytes_total"))
                assertEquals(3.0, total("""souz_attachment_save_rejected_total\{scope="execution",?}"""))
                assertEquals(2.0, total("""souz_attachment_save_rejected_total\{scope="owner",?}"""))
            }

            ageSaveCharges(hours = 23)
            assertAttachmentError(ATTACHMENT_OWNER_SAVE_LIMIT) { storage.saveFile(owner, third, "new-window.txt", "1") }
            ageSaveCharges(hours = 25)
            val renewed = storage.saveFile(owner, third, "new-window.txt", "1")
            assertEquals(0, storage.cleanup())
            sql { connection ->
                connection.createStatement().use {
                    it.executeQuery("select id from attachment_save_charges").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(renewed.id, rows.getObject("id", UUID::class.java))
                        assertFalse(rows.next())
                    }
                }
            }
            backend.createPeer().use { peer ->
                val peerStorage = peer.attachmentStorage
                assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) { peerStorage.saveFile(owner, first, "another.txt", "") }
                assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) { peerStorage.saveFile(owner, second, "another.txt", "1") }
                peerStorage.saveFile(owner, fourth, "peer-window.txt", "1")
            }
        }
    }

    @Test
    fun `same-owner waiters leave a connection and transfer slot for other owners`() =
        backendE2eTest(
            "attachment_budget_pool",
            attachments = AttachmentMinio.config(maxConcurrentUploads = 2),
            configureApp = { it.copy(postgres = it.postgres.copy(maxPoolSize = 2)) },
        ) {
            val owner = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            val execution = executionsFor(owner).single()
            val otherExecution = executionsFor(other).single()
            val storage = backend.attachmentStorage
            withPeerBackend { peer ->
                peer.withSaveBudgetLocked(owner) { awaitWaiters ->
                    launch { storage.saveFile(owner, execution, "first.txt", "first") }
                    awaitWaiters(1)
                    launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        storage.saveFile(owner, execution, "second.txt", "second")
                    }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        storage.saveFile(owner, execution, "cancelled.txt", "cancelled")
                    }.cancelAndJoin()
                    withTimeout(5_000) { storage.saveFile(other, otherExecution, "independent.txt", "independent") }
                }
            }
        }

    @Test
    fun `failed and cancelled PUTs remain charged after pending cleanup and execution deletion`() {
        val clock = AttachmentClock()
        val budget = AttachmentSaveBudget(2, 100, 2, 100)
        val config = AttachmentMinio.config(budget)
        backendE2eTest("attachment_budget_failed", clock = clock, attachments = config) {
            val owner = UUID.randomUUID().toString()
            val (execution, next) = executionsFor(owner, 2)
            val missingBucket = AttachmentStorageConfig(
                config.endpoint, "missing-${UUID.randomUUID()}", "attachment-test", "attachment-test-secret", saveBudget = budget,
            )
            backend.createPeer(attachments = missingBucket).use { peer ->
                val failure = assertFailsWith<S3Exception> {
                    peer.attachmentStorage.saveFile(owner, execution, "failed.txt", "content")
                }
                assertEquals(404, failure.statusCode())
            }

            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.use {
                    check(it.requestMethod == "PUT")
                    it.requestBody.readAllBytes()
                    started.complete(Unit)
                    if (release.await(15, TimeUnit.SECONDS)) it.sendResponseHeaders(200, -1)
                }
            }
            server.start()
            try {
                val stalledPut = AttachmentStorageConfig(
                    "http://127.0.0.1:${server.address.port}", "blocked-put", "test-access", "test-secret", saveBudget = budget,
                )
                backend.createPeer(attachments = stalledPut).use { peer ->
                    withTimeout(10_000) {
                        val storage = peer.attachmentStorage
                        val upload = launch { storage.saveFile(owner, execution, "cancelled.txt", "content") }
                        try {
                            started.await()
                            assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) {
                                storage.saveFile(owner, execution, "extra.txt", "content")
                            }
                        } finally {
                            upload.cancel()
                            release.countDown()
                        }
                        upload.join()
                    }
                }
            } finally {
                server.stop(0)
            }

            val storage = backend.attachmentStorage
            for (file in listOf("failed.txt", "cancelled.txt")) {
                assertAttachmentError("attachment_not_ready") { storage.saveFile(owner, execution, file, "content") }
            }
            clock.now = clock.now.plus(ATTACHMENT_PENDING_LIFETIME)
            assertEquals(2, storage.cleanup())
            assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) { storage.saveFile(owner, execution, "failed.txt", "content") }
            sql { connection ->
                connection.prepareStatement("delete from agent_executions where id = ?").use {
                    it.setObject(1, execution)
                    assertEquals(1, it.executeUpdate())
                }
                connection.createStatement().use {
                    it.executeQuery("select 1 from attachment_execution_save_totals").use { rows -> assertFalse(rows.next()) }
                }
            }
            assertAttachmentError(ATTACHMENT_OWNER_SAVE_LIMIT) { storage.saveFile(owner, next, "later.txt", "content") }
        }
    }

    private suspend fun BackendE2eScope.executionsFor(owner: String, count: Int = 1): List<UUID> {
        val chat = UUID.fromString(createPublicChat(owner))
        return List(count) {
            val turn = backend.dependencies.executionService.executeChatTurn(owner, chat, "Create an attachment")
            turn.execution.id.also { backend.awaitExecution(it) }
        }
    }

    private suspend fun AttachmentStorage.saveFile(owner: String, execution: UUID, name: String, content: String) =
        saveWorkspaceOutput(owner, execution, name) { content.byteInputStream() }

    private fun BackendE2eScope.ageSaveCharges(hours: Int) = sql { connection ->
        connection.prepareStatement(
            "update attachment_save_charges set admitted_at = statement_timestamp() - make_interval(hours => ?)",
        ).use {
            it.setInt(1, hours)
            assertEquals(5, it.executeUpdate())
        }
    }

    private suspend fun BackendE2eScope.assertBudgetRace(
        peer: BackendE2eScope,
        owner: String,
        limit: String,
        save: suspend AttachmentStorage.(replica: Int) -> Unit,
    ) {
        val outcomes = peer.withSaveBudgetLocked(owner) { awaitWaiters ->
            listOf(backend, peer.backend).mapIndexed { replica, host ->
                async {
                    try {
                        host.attachmentStorage.save(replica)
                        null
                    } catch (error: AttachmentException) {
                        error.code
                    }
                }
            }.also { awaitWaiters(2) }
        }.awaitAll()
        assertEquals(setOf(null, limit), outcomes.toSet())
    }

    private suspend fun <T> BackendE2eScope.withSaveBudgetLocked(
        owner: String,
        block: suspend CoroutineScope.(awaitWaiters: suspend (Int) -> Unit) -> T,
    ): T = withContext(Dispatchers.IO) {
        backend.dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            val pid = blocker.prepareStatement(
                "select pg_backend_pid() from pg_advisory_xact_lock(hashtextextended(?, 0))",
            ).use {
                it.setString(1, "attachment-save-budget:$owner")
                it.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
            try {
                block { expected ->
                    eventually("$expected workspace saves waiting for the owner lock") {
                        val waiting = sql { observer ->
                            observer.prepareStatement("""
                                select count(*) from pg_locks
                                where locktype = 'advisory' and not granted and ? = any(pg_blocking_pids(pid))
                            """.trimIndent()).use {
                                it.setInt(1, pid)
                                it.executeQuery().use { rows ->
                                    check(rows.next())
                                    rows.getInt(1)
                                }
                            }
                        }
                        Unit.takeIf { waiting == expected }
                    }
                }
            } finally {
                blocker.rollback()
            }
        }
    }
}
