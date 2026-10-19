package ru.souz.backend.attachments

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import software.amazon.awssdk.services.s3.model.S3Exception
import ru.souz.backend.crypto.sha256Hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.souz.backend.app.backendDiModule
import ru.souz.backend.storage.postgres.PostgresAttachmentRepository
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.backend.storage.postgres.write

class AttachmentStorageTest {
    @Test
    fun `missing bucket fails uploads and cleanup with safe diagnostics`() = attachmentTest {
        val missing = AttachmentStorageConfig(
            endpoint = config.endpoint, bucket = "missing-${UUID.randomUUID()}",
            accessKey = "attachment-test", secretKey = "attachment-test-secret",
        )
        val logger = LoggerFactory.getLogger(AttachmentStorage::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            S3AttachmentObjects(missing).use { unavailable ->
                val failedStorage = AttachmentStorage(missing, repository, unavailable, clock)
                assertEquals(404, assertFailsWith<S3Exception> {
                    failedStorage.save("alice", "missing-bucket", "a.txt", "bytes".byteInputStream())
                }.statusCode())
                assertEquals(0, failedStorage.cleanup())
            }
            val file = repository.expire(clock.now).single()
            val failure = logs.list.single()
            assertTrue(failure.formattedMessage.contains("file ${file.id}: exceptionClass=software.amazon.awssdk.services.s3.model."))
            assertTrue(failure.formattedMessage.contains("statusCode=404, s3ErrorCode=NoSuchBucket"))
            assertNull(failure.throwableProxy)
            for (privateValue in listOf(missing.endpoint, missing.bucket, missing.accessKey, missing.secretKey)) {
                assertTrue(!failure.formattedMessage.contains(privateValue))
            }
            assertEquals(1, storage.cleanup())
            assertNull(repository.get("alice", file.id))
            assertEquals(0, storage.cleanup())
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    @Test
    fun `lowering the upload limit preserves reads of previously stored files`() = attachmentTest {
        val bytes = ByteArray(1000) { it.toByte() }
        val file = storage.save("alice", "before-limit-change", "a.bin", bytes.inputStream())
        val smallerConfig = AttachmentStorageConfig(
            endpoint = config.endpoint, bucket = config.bucket,
            accessKey = config.accessKey, secretKey = config.secretKey, maxFileBytes = 500,
        )
        val smallerLimit = AttachmentStorage(smallerConfig, repository, objects, clock)
        assertContentEquals(bytes, smallerLimit.read("alice", file.id))
        assertEquals("attachment_too_large", assertFailsWith<AttachmentException> {
            smallerLimit.save("alice", "after-limit-change", "a.bin", bytes.inputStream())
        }.code)
    }

    @Test
    fun `reads share upload slots and cancelling a waiting read does not leak a permit`() = attachmentTest(maxConcurrentUploads = 1) {
        val file = save("alice", "readable", "a.txt", "content")
        withTimeout(10_000) {
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                // InputStream.read is a blocking JVM boundary, called on Dispatchers.IO.
                val release = CountDownLatch(1)
                val input = object : InputStream() {
                    override fun read(): Int {
                        entered.complete(Unit)
                        release.await()
                        return -1
                    }
                }
                val upload = async { storage.save("alice", "blocked", "empty.txt", input) }
                try {
                    entered.await()
                    val cancelled = async(start = CoroutineStart.UNDISPATCHED) { storage.read("alice", file.id) }
                    cancelled.cancelAndJoin()
                    val read = async(start = CoroutineStart.UNDISPATCHED) { storage.read("alice", file.id) }
                    assertEquals(null, withTimeoutOrNull(250) { read.await() })
                    release.countDown()
                    upload.await()
                    assertEquals("content", read.await().decodeToString())
                    assertEquals("content", storage.read("alice", file.id).decodeToString())
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun `cleanup batches share one limit across expired files abandoned uploads and retries`() = attachmentTest {
        val expected = mutableSetOf<UUID>()
        repeat(3) { index ->
            expected += repository.reserve(
                "alice", "retry-$index", "a.txt", "text/plain", 0, "", clock.now.minus(Duration.ofDays(2)),
            ).id
        }
        assertEquals(expected, repository.expire(clock.now, 3).map { it.id }.toSet())
        repeat(3) { index ->
            val old = clock.now.minus(Duration.ofDays(31))
            val ready = repository.reserve("alice", "ready-$index", "a.txt", "text/plain", 0, "", old)
            expected += repository.complete(ready, old).id
            expected += repository.reserve(
                "alice", "pending-$index", "a.txt", "text/plain", 0, "", clock.now.minus(Duration.ofMinutes(10)),
            ).id
        }
        val fresh = repository.reserve("alice", "fresh-ready", "a.txt", "text/plain", 0, "", clock.now)
        repository.complete(fresh, clock.now)
        repository.reserve("alice", "fresh-pending", "a.txt", "text/plain", 0, "", clock.now)

        val processed = mutableSetOf<UUID>()
        for (size in listOf(4, 4, 1)) {
            val batch = repository.expire(clock.now, 4)
            assertEquals(size, batch.size)
            for (file in batch) {
                assertEquals("deleting", file.state)
                assertTrue(processed.add(file.id))
                repository.remove(file)
            }
        }
        assertEquals(expected, processed)
        assertTrue(repository.expire(clock.now, 4).isEmpty())
    }

    @Test
    fun `emoji at filename limit survives persistence and retries without split surrogates`() = attachmentTest {
        val cases = listOf(
            "😀.txt" to "😀.txt",
            "a".repeat(178) + "😀.txt" to "a".repeat(178) + "😀",
            "a".repeat(179) + "😀.txt" to "a".repeat(179),
            "a".repeat(180) + "😀.txt" to "a".repeat(180),
        )
        for ((index, names) in cases.withIndex()) {
            val (original, expected) = names
            val file = save("alice", "emoji-$index", original, "content")
            assertEquals(expected, file.name)
            assertEquals(expected, storage.get("alice", file.id).name)
            assertEquals(file, save("alice", "emoji-$index", original, "content"))
            assertEquals("content", storage.read("alice", file.id).decodeToString())
        }
    }

    @Test
    fun `pending upload cannot be resumed and is removed at ten minutes`() = attachmentTest {
        val bytes = "pending bytes".toByteArray()
        val file = repository.reserve("alice", "pending", "a.txt", "text/plain", bytes.size.toLong(), bytes.sha256Hex(), clock.now)
        assertEquals("attachment_not_ready", assertFailsWith<AttachmentException> {
            storage.save("alice", "pending", "a.txt", bytes.inputStream())
        }.code)
        assertEquals(404, assertFailsWith<S3Exception> { objects.read(file) }.statusCode())
        clock.now = clock.now.plus(Duration.ofMinutes(10)).minusSeconds(1)
        assertEquals(0, storage.cleanup())
        clock.now = clock.now.plusSeconds(1)
        assertEquals(1, storage.cleanup())
        assertNull(repository.get("alice", file.id))
        assertEquals("attachment_expired", assertFailsWith<AttachmentException> { repository.complete(file, clock.now) }.code)
        assertEquals("attachment_not_found", assertFailsWith<AttachmentException> { storage.get("alice", file.id) }.code)
        assertEquals(0, storage.cleanup())
    }

    @Test
    fun `private per-user objects survive restart and deny foreign reads`() = attachmentTest {
        val first = save("alice", "event", "../report.md", "# Привет")
        val second = save("bob", "event", "report.md", "# Привет")
        assertNotEquals(first.objectKey.substringBeforeLast('/'), second.objectKey.substringBeforeLast('/'))
        val denied = AttachmentMinio.server.execInContainer("mc", "mb", "${config.accessKey}/forbidden-${config.bucket}")
        assertTrue(denied.exitCode != 0 && denied.stderr.contains("Access Denied", ignoreCase = true), denied.stderr)
        val another = save("alice", "another-event", "a.txt", "another")
        assertEquals(first.objectKey.substringBeforeLast('/'), another.objectKey.substringBeforeLast('/'))
        assertEquals(null, storage.find("bob", "another-event"))
        assertEquals("report.md", first.name)
        assertEquals("text/markdown", first.mimeType)
        assertEquals("attachment_not_found", assertFailsWith<AttachmentException> { storage.read("bob", first.id) }.code)
        val anonymous = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("${config.endpoint}/${config.bucket}/${first.objectKey}")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(403, anonymous.statusCode())
        val restarted = AttachmentStorage(config, PostgresAttachmentRepository(dataSource), objects, clock)
        assertEquals(first, restarted.get("alice", first.id))
        assertEquals("# Привет", restarted.read("alice", first.id).decodeToString())
        assertEquals(second, restarted.find("bob", "event"))
    }

    @Test
    fun `ready duplicates preserve identity and expiry while cleanup releases the source key`() = attachmentTest {
        val first = save("alice", "same-event", "a.txt", "original")
        coroutineScope {
            val a = async { save("alice", "same-event", "a.txt", "original") }
            val b = async { save("alice", "same-event", "a.txt", "original") }
            assertEquals(first, a.await())
            assertEquals(first, b.await())
        }
        clock.now = clock.now.plus(Duration.ofDays(29))
        assertEquals(first, save("alice", "same-event", "a.txt", "original"))
        assertEquals("attachment_idempotency_conflict", assertFailsWith<AttachmentException> {
            save("alice", "same-event", "a.txt", "replacement")
        }.code)
        clock.now = first.expiresAt!!
        assertEquals("attachment_expired", assertFailsWith<AttachmentException> { storage.read("alice", first.id) }.code)
        assertEquals(1, storage.cleanup())
        assertEquals(0, storage.cleanup())
        assertEquals(404, assertFailsWith<S3Exception> { objects.read(first) }.statusCode())
        assertNull(repository.get("alice", first.id))
        assertEquals("attachment_not_found", assertFailsWith<AttachmentException> { storage.get("alice", first.id) }.code)
        val replacement = save("alice", "same-event", "a.txt", "original")
        assertNotEquals(first.id, replacement.id)
        assertEquals("original", storage.read("alice", replacement.id).decodeToString())
    }

    @Test
    fun `failed upload and deletion commits leave the file eligible for cleanup`() = attachmentTest {
        execute("""create function reject_attachment() returns trigger language plpgsql as 'begin raise exception ''test failure''; end'""")
        execute("""create constraint trigger reject_attachment after update on stored_attachments deferrable initially deferred for each row when (new.state = 'ready') execute function reject_attachment()""")
        assertFailsWith<java.sql.SQLException> { save("alice", "interrupted", "a.txt", "persisted") }
        execute("drop trigger reject_attachment on stored_attachments")
        assertEquals("attachment_expired", assertFailsWith<AttachmentException> {
            save("alice", "interrupted", "a.txt", "persisted")
        }.code)
        val failed = repository.expire(clock.now).single()
        assertEquals("deleting", failed.state)
        assertContentEquals("persisted".toByteArray(), objects.read(failed))
        execute("""create constraint trigger reject_delete after delete on stored_attachments deferrable initially deferred for each row execute function reject_attachment()""")
        assertEquals(0, storage.cleanup())
        assertEquals(404, assertFailsWith<S3Exception> { objects.read(failed) }.statusCode())
        assertEquals("deleting", repository.get("alice", failed.id)?.state)
        execute("drop trigger reject_delete on stored_attachments")
        assertEquals(1, storage.cleanup())
        assertNull(repository.get("alice", failed.id))
        assertEquals(0, storage.cleanup())
    }

    @Test
    fun `oversized and interrupted streams fail before persistence`() = attachmentTest {
        assertEquals("attachment_too_large", assertFailsWith<AttachmentException> {
            storage.save("alice", "large", "large.txt", ByteArray(config.maxFileBytes + 1).inputStream())
        }.code)
        val broken = object : InputStream() { override fun read(): Int = throw java.io.IOException("broken") }
        assertFailsWith<java.io.IOException> { storage.save("alice", "broken", "a.txt", broken) }
        assertEquals(null, storage.find("alice", "large"))
        assertEquals(null, storage.find("alice", "broken"))
        assertEquals(0, storage.cleanup())
    }
}

internal object AttachmentMinio {
    // Public source build; pin the multi-platform index for both CI (amd64) and local arm64.
    // Build recipe: https://github.com/coollabsio/minio
    private const val IMAGE = "ghcr.io/coollabsio/minio:RELEASE.2025-10-15T17-29-55Z" +
        "@sha256:69b55a1c1c5dc285ce04db96689f5b2102317fc77a50680a1874ca6efd1c87f9"

    val server = GenericContainer<Nothing>(IMAGE).apply {
        withEnv("MINIO_ROOT_USER", "attachment-test")
        withEnv("MINIO_ROOT_PASSWORD", "attachment-test-secret")
        withCommand("server", "/data")
        withExposedPorts(9000)
        waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))
        start()
        check(execInContainer("mc", "alias", "set", "root", "http://localhost:9000", "attachment-test", "attachment-test-secret").exitCode == 0)
    }

    fun provision(config: AttachmentStorageConfig) {
        val policy = """{"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],
            "Resource":["arn:aws:s3:::${config.bucket}/*"]}]}"""
        val path = "/tmp/${config.bucket}.json"
        server.copyFileToContainer(Transferable.of(policy), path)
        for (args in listOf(
            listOf("mb", "root/${config.bucket}"),
            listOf("admin", "user", "add", "root", config.accessKey, config.secretKey),
            listOf("admin", "policy", "create", "root", config.bucket, path),
            listOf("admin", "policy", "attach", "root", config.bucket, "--user", config.accessKey),
            listOf("alias", "set", config.accessKey, "http://localhost:9000", config.accessKey, config.secretKey),
        )) {
            val result = server.execInContainer("mc", *args.toTypedArray())
            check(result.exitCode == 0) { "MinIO test provisioning failed: ${result.stderr}" }
        }
    }
}

private class AttachmentClock(var now: Instant = Instant.parse("2026-10-13T00:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

private fun attachmentTest(maxConcurrentUploads: Int = 16, block: suspend AttachmentFixture.() -> Unit) = runBlocking {
    AttachmentFixture(maxConcurrentUploads).use { fixture ->
        PostgresUserRepository(fixture.dataSource).ensureUser("alice")
        PostgresUserRepository(fixture.dataSource).ensureUser("bob")
        fixture.block()
    }
}

private class AttachmentFixture(maxConcurrentUploads: Int) : AutoCloseable {
    private val appConfig = postgresAppConfig(newPostgresSchema("attachments"))
    val dataSource = PostgresDataSourceFactory.create(appConfig.postgres)
    val clock = AttachmentClock()
    private val storageId = UUID.randomUUID().toString()
    val config = AttachmentStorageConfig(
        endpoint = "http://${AttachmentMinio.server.host}:${AttachmentMinio.server.firstMappedPort}",
        bucket = "attachments-$storageId",
        accessKey = "user-$storageId", secretKey = "attachment-user-secret", maxFileBytes = 1024,
        maxConcurrentUploads = maxConcurrentUploads,
    )
    init { AttachmentMinio.provision(config) }

    val di = DI {
        import(backendDiModule("Attachment test", appConfig.copy(attachments = config), dataSourceFactory = { dataSource }))
        bindSingleton<Clock>(overrides = true) { clock }
    }.direct
    val storage: AttachmentStorage = di.instance()
    val objects: S3AttachmentObjects = di.instance()
    val repository: PostgresAttachmentRepository = di.instance()
    suspend fun save(user: String, key: String, name: String, text: String) = storage.save(user, key, name, text.byteInputStream())
    suspend fun execute(sql: String) = dataSource.write { it.createStatement().use { statement -> statement.execute(sql) } }
    override fun close() { objects.close(); dataSource.close() }
}
