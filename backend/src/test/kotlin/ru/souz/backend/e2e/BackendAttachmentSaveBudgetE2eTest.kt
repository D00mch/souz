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
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
import ru.souz.backend.attachments.S3AttachmentObjects
import ru.souz.backend.attachments.StoredAttachment
import ru.souz.backend.attachments.assertAttachmentError
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.storage.postgres.PostgresAttachmentRepository
import software.amazon.awssdk.services.s3.model.S3Exception

class BackendAttachmentSaveBudgetE2eTest {
    @Test
    fun `owner and global capacity rejections roll back workspace save charges with their reservation`() {
        for (global in listOf(false, true)) {
            val clock = AttachmentClock()
            val config = AttachmentMinio.config(AttachmentSaveBudget(1, 3, 1, 3),
                maxOwnerFiles = if (global) 1_000 else 1, maxTotalFiles = if (global) 1 else 100_000)
            backendE2eTest("attachment_budget_capacity", attachments = config, clock = clock) {
                val owner = UUID.randomUUID().toString()
                val execution = executionFor(owner)
                val storage = backend.attachmentStorage
                val blockerOwner = if (global) UUID.randomUUID().toString() else owner
                val blocker = storage.save(blockerOwner, "existing", "existing.txt", "x".byteInputStream())
                assertAttachmentError("attachment_quota_exceeded") {
                    storage.saveFile(owner, execution, "report.txt", "123")
                }
                assertEquals(blocker, storage.get(blockerOwner, blocker.id))
                clock.now = blocker.expiresAt!!
                assertEquals(1, storage.cleanup())
                val saved = storage.saveFile(owner, execution, "report.txt", "123")
                assertEquals(saved, storage.saveFile(owner, execution, "report.txt", "123"))
                assertTrue("souz_attachment_save_admitted_bytes_total 3.0" in client.get("/metrics").bodyAsText())
                assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) {
                    storage.saveFile(owner, execution, "extra.txt", "")
                }
            }
        }
    }

    @Test
    fun `count and bytes are independent in both scopes and ready duplicates are free`() {
        val config = AttachmentMinio.config(AttachmentSaveBudget(2, 5, 4, 10))
        backendE2eTest("attachment_budget_boundaries", attachments = config) {
            val owner = UUID.randomUUID().toString()
            val first = executionFor(owner)
            val second = executionFor(owner)
            val third = executionFor(owner)
            val storage = backend.attachmentStorage

            val legacyKey = "$first:output:${"legacy".sha256Hex()}:${"legacy.txt".sha256Hex()}"
            val legacy = storage.save(owner, legacyKey, "legacy.txt", "legacy".byteInputStream())
            assertAttachmentError("attachment_execution_required") {
                storage.saveFile(owner, UUID.randomUUID(), "orphan.txt", "12345")
            }
            assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) { storage.saveFile(owner, first, "large.txt", "123456") }
            val original = storage.saveFile(owner, first, "report.txt", "123")
            storage.saveFile(owner, first, "report.txt", "45") // Same name, different bytes.
            assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) { storage.saveFile(owner, first, "empty.txt", "") }
            assertEquals(original, storage.saveFile(owner, first, "report.txt", "123"))
            assertEquals(legacy, storage.saveFile(owner, first, "legacy.txt", "legacy"))
            storage.saveFile(owner, second, "full.txt", "12345")
            assertAttachmentError(ATTACHMENT_OWNER_SAVE_LIMIT) { storage.saveFile(owner, third, "extra.txt", "6") }
            storage.saveFile(owner, third, "empty.txt", "")
            assertAttachmentError(ATTACHMENT_OWNER_SAVE_LIMIT) { storage.saveFile(owner, third, "another.txt", "") }

            val other = UUID.randomUUID().toString()
            storage.saveFile(other, executionFor(other), "report.txt", "1")
            val objects = AttachmentMinio.server.execInContainer("mc", "ls", "--recursive", "root/${config.bucket}")
            assertEquals(0, objects.exitCode)
            assertEquals(6, objects.stdout.lineSequence().count { it.isNotBlank() })
            val metrics = client.get("/metrics").bodyAsText()
            assertTrue("souz_attachment_save_admitted_bytes_total 11.0" in metrics)
            assertTrue(Regex("""souz_attachment_save_rejected_total\{scope="execution",?} 2\.0""").containsMatchIn(metrics))
            assertTrue(Regex("""souz_attachment_save_rejected_total\{scope="owner",?} 2\.0""").containsMatchIn(metrics))

            sql { connection ->
                connection.createStatement().use {
                    assertEquals(5, it.executeUpdate("""
                        update attachment_save_charges set admitted_at = statement_timestamp() - interval '25 hours'
                    """.trimIndent()))
                }
            }
            assertEquals(0, storage.cleanup())
            sql { connection ->
                connection.createStatement().use {
                    it.executeQuery("select count(*) from attachment_save_charges").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(0, rows.getInt(1))
                    }
                }
            }
            withPeerBackend { peer ->
                val restartedStorage = peer.backend.attachmentStorage
                assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) {
                    restartedStorage.saveFile(owner, first, "another.txt", "")
                }
                assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) {
                    restartedStorage.saveFile(owner, second, "another.txt", "1")
                }
                restartedStorage.saveFile(owner, third, "new-window.txt", "1")
            }
        }
    }

    @Test
    fun `two replicas admit at most the execution and owner allowances during concurrent saves`() =
        backendE2eTest("attachment_budget_race", attachments = AttachmentMinio.config(AttachmentSaveBudget(2, 20, 3, 30))) {
            val owner = UUID.randomUUID().toString()
            val first = executionFor(owner)
            val second = executionFor(owner)
            val third = executionFor(owner)
            withPeerBackend { peer ->
                val replicas = listOf(backend.attachmentStorage, peer.backend.attachmentStorage)
                val executionRace = race(List(8) { index ->
                    suspend { replicas[index % 2].saveFile(owner, first, "report-$index.txt", "content") }
                })
                assertEquals(2, executionRace.count { it == null })
                assertEquals(6, executionRace.count { it == ATTACHMENT_EXECUTION_SAVE_LIMIT })
                val ownerRace = race(List(8) { index ->
                    suspend { replicas[index % 2].saveFile(owner, if (index % 2 == 0) second else third, "report-$index.txt", "content") }
                })
                assertEquals(1, ownerRace.count { it == null })
                assertEquals(7, ownerRace.count { it == ATTACHMENT_OWNER_SAVE_LIMIT })
            }
        }

    @Test
    fun `saves waiting behind their owner leave database and transfer capacity for other owners`() =
        backendE2eTest(
            "attachment_budget_pool",
            attachments = AttachmentMinio.config(maxConcurrentUploads = 2),
            configureApp = { it.copy(postgres = it.postgres.copy(maxPoolSize = 2)) },
        ) {
            val owner = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            val execution = executionFor(owner)
            val otherExecution = executionFor(other)
            val storage = backend.attachmentStorage
            withPeerBackend { peer ->
                withContext(Dispatchers.IO) {
                    peer.backend.dataSource.connection.use { blocker ->
                        blocker.autoCommit = false
                        val blockerPid = blocker.prepareStatement(
                            "select pg_backend_pid() from pg_advisory_xact_lock(hashtextextended(?, 0))",
                        ).use {
                            it.setString(1, "attachment-save-budget:$owner")
                            it.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
                        }
                        val first = async { storage.saveFile(owner, execution, "first.txt", "first") }
                        val second = try {
                            eventually("one workspace save blocked in PostgreSQL") {
                                peer.sql { connection ->
                                    connection.prepareStatement("""
                                        select exists (select 1 from pg_locks
                                        where not granted and locktype = 'advisory' and ? = any(pg_blocking_pids(pid)))
                                    """.trimIndent()).use {
                                        it.setInt(1, blockerPid)
                                        it.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
                                    }
                                }.takeIf { it }
                            }
                            // Start on the same IO dispatcher so these calls run to their first suspension.
                            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                                storage.saveFile(owner, execution, "second.txt", "second")
                            }
                            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                                storage.saveFile(owner, execution, "cancelled.txt", "cancelled")
                            }
                            cancelled.cancelAndJoin()
                            assertEquals(1, backend.dataSource.hikariPoolMXBean.activeConnections)
                            assertEquals(other, withTimeout(5_000) {
                                storage.saveFile(other, otherExecution, "independent.txt", "independent")
                            }.userId)
                            waiting
                        } finally {
                            blocker.rollback()
                        }
                        assertEquals(listOf("first.txt", "second.txt"), awaitAll(first, second).map { it.name })
                    }
                }
            }
        }

    @Test
    fun `failed and cancelled PUTs stay charged after cleanup and execution deletion until the owner window ends`() {
        val clock = AttachmentClock()
        val budget = AttachmentSaveBudget(2, 100, 2, 100)
        val config = AttachmentMinio.config(budget)
        backendE2eTest("attachment_budget_failed", clock = clock, attachments = config) {
            val owner = UUID.randomUUID().toString()
            val execution = executionFor(owner)
            val rejectedPut = AttachmentStorageConfig(
                config.endpoint, "missing-${UUID.randomUUID()}", "attachment-test", "attachment-test-secret",
            )
            withStorage(rejectedPut, budget, clock) { unavailable ->
                val failure = assertFailsWith<S3Exception> { unavailable.saveFile(owner, execution, "failed.txt", "content") }
                assertEquals(404, failure.statusCode())
            }

            val storage = backend.attachmentStorage
            assertAttachmentError("attachment_not_ready") { storage.saveFile(owner, execution, "failed.txt", "content") }
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                try {
                    exchange.requestBody.use { it.readAllBytes() }
                    started.complete(Unit)
                    if (release.await(10, TimeUnit.SECONDS)) exchange.sendResponseHeaders(200, -1)
                } finally {
                    exchange.close()
                }
            }
            server.start()
            try {
                val blockedPut = AttachmentStorageConfig(
                    "http://127.0.0.1:${server.address.port}", "blocked-put", "test-access", "test-secret",
                )
                withStorage(blockedPut, budget, clock) { blocked ->
                    withTimeout(15_000) {
                        val upload = async { blocked.saveFile(owner, execution, "cancelled.txt", "content") }
                        try {
                            started.await()
                            // The next reservation can reach admission while this PUT is still blocked.
                            withTimeout(5_000) {
                                assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) {
                                    blocked.saveFile(owner, execution, "extra.txt", "content")
                                }
                            }
                            upload.cancel()
                        } finally {
                            release.countDown()
                        }
                        upload.join()
                        assertTrue(upload.isCancelled)
                    }
                }
            } finally {
                release.countDown()
                server.stop(0)
            }
            assertAttachmentError("attachment_not_ready") { storage.saveFile(owner, execution, "cancelled.txt", "content") }
            clock.now = clock.now.plus(ATTACHMENT_PENDING_LIFETIME)
            assertEquals(2, storage.cleanup())
            assertAttachmentError(ATTACHMENT_EXECUTION_SAVE_LIMIT) { storage.saveFile(owner, execution, "failed.txt", "content") }

            val next = executionFor(owner)
            sql { connection ->
                connection.prepareStatement("delete from agent_executions where id = ?").use {
                    it.setObject(1, execution)
                    assertEquals(1, it.executeUpdate())
                }
                connection.createStatement().use {
                    it.executeQuery("select count(*) from attachment_execution_save_totals").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(0, rows.getInt(1))
                    }
                    assertEquals(2, it.executeUpdate("""
                        update attachment_save_charges set admitted_at = statement_timestamp() - interval '23 hours'
                    """.trimIndent()))
                }
            }
            assertAttachmentError(ATTACHMENT_OWNER_SAVE_LIMIT) { storage.saveFile(owner, next, "later.txt", "content") }
            sql { connection ->
                connection.createStatement().use {
                    assertEquals(2, it.executeUpdate("update attachment_save_charges set admitted_at = admitted_at - interval '2 hours'"))
                }
            }
            storage.saveFile(owner, next, "later.txt", "content")
            assertEquals(0, storage.cleanup())
            sql { connection ->
                connection.createStatement().use {
                    it.executeQuery("select count(*) from attachment_save_charges").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }
        }
    }

    private suspend fun BackendE2eScope.executionFor(owner: String): UUID {
        val chat = createPublicChat(owner, requestId = UUID.randomUUID().toString())
        val started = backend.dependencies.executionService.executeChatTurn(owner, UUID.fromString(chat), "Create an attachment",
            requestOverrides = UserSettingsOverrides(defaultModel = E2E_LOCAL_MODEL.alias))
        return started.execution.id.also { backend.awaitExecution(it) }
    }

    private suspend fun BackendE2eScope.withStorage(
        config: AttachmentStorageConfig,
        budget: AttachmentSaveBudget,
        clock: AttachmentClock,
        block: suspend (AttachmentStorage) -> Unit,
    ) {
        S3AttachmentObjects(config).use { objects ->
            block(AttachmentStorage(config, PostgresAttachmentRepository(backend.dataSource, budget), objects, clock))
        }
    }

    private suspend fun AttachmentStorage.saveFile(owner: String, execution: UUID, name: String, content: String) =
        saveWorkspaceOutput(owner, execution, name) { content.byteInputStream() }

    private suspend fun race(attempts: List<suspend () -> StoredAttachment>): List<String?> = coroutineScope {
        val ready = CompletableDeferred<Unit>()
        val calls = attempts.map { attempt ->
            async {
                ready.await()
                try {
                    attempt()
                    null
                } catch (error: AttachmentException) {
                    error.code
                }
            }
        }
        ready.complete(Unit)
        calls.awaitAll()
    }
}
