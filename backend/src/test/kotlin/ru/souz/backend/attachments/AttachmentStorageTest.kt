package ru.souz.backend.attachments

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
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
    fun `late PUT after expired pending upload cleanup remains eligible for deletion`() = attachmentTest {
        val repository = PostgresAttachmentRepository(dataSource)
        val bytes = "late bytes".toByteArray()
        val file = repository.reserve("alice", "late-write", "a.txt", "text/plain", bytes.size.toLong(), bytes.sha256Hex(), clock.now)
        clock.now = clock.now.plus(Duration.ofHours(24))
        assertEquals(1, storage.cleanup())
        // Simulate an already-started PUT reaching S3 after the cleanup completed.
        objects.put(file, bytes)
        assertEquals("deleted", repository.complete(file, clock.now).state)
        repository.retireLateWrite(file)
        assertEquals("attachment_expired", assertFailsWith<AttachmentException> { storage.get("alice", file.id) }.code)
        assertEquals(1, storage.cleanup())
        assertEquals(0, storage.cleanup())
        assertEquals(404, assertFailsWith<S3Exception> { objects.read(file, config.maxFileBytes) }.statusCode())
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
    fun `missing configured bucket is not created by storage`() = attachmentTest {
        val missing = AttachmentStorageConfig(
            endpoint = config.endpoint, bucket = "missing-${UUID.randomUUID()}",
            accessKey = "attachment-test", secretKey = "attachment-test-secret",
        )
        S3AttachmentObjects(missing).use { unavailable ->
            val storage = AttachmentStorage(missing, PostgresAttachmentRepository(dataSource), unavailable, clock)
            val error = assertFailsWith<S3Exception> {
                storage.save("alice", "missing-bucket", "a.txt", "bytes".byteInputStream())
            }
            assertEquals(404, error.statusCode())
        }
    }

    @Test
    fun `concurrent retry preserves identity bytes and expiry without prolongation`() = attachmentTest {
        val first = coroutineScope {
            val a = async { save("alice", "same-event", "a.txt", "original") }
            val b = async { save("alice", "same-event", "a.txt", "original") }
            a.await().also { assertEquals(it, b.await()) }
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
        assertFailsWith<Exception> { objects.read(first, config.maxFileBytes) }
        assertEquals("attachment_expired", assertFailsWith<AttachmentException> {
            save("alice", "same-event", "a.txt", "original")
        }.code)
    }

    @Test
    fun `failed database commit after PUT recovers and abandoned uploads are cleaned`() = attachmentTest {
        execute("""create function reject_attachment() returns trigger language plpgsql as 'begin raise exception ''test failure''; end'""")
        execute("""create constraint trigger reject_attachment after update on stored_attachments deferrable initially deferred for each row execute function reject_attachment()""")
        assertFailsWith<java.sql.SQLException> { save("alice", "interrupted", "a.txt", "persisted") }
        execute("drop trigger reject_attachment on stored_attachments")
        val recovered = save("alice", "interrupted", "a.txt", "persisted")
        assertContentEquals("persisted".toByteArray(), storage.read("alice", recovered.id))
        execute("update stored_attachments set state = 'pending', stored_at = null, expires_at = null")
        clock.now = clock.now.plus(Duration.ofHours(24))
        assertEquals(1, storage.cleanup())
        assertFailsWith<Exception> { objects.read(recovered, config.maxFileBytes) }
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

private fun attachmentTest(block: suspend AttachmentFixture.() -> Unit) = runBlocking {
    AttachmentFixture().use { fixture ->
        PostgresUserRepository(fixture.dataSource).ensureUser("alice")
        PostgresUserRepository(fixture.dataSource).ensureUser("bob")
        fixture.block()
    }
}

private class AttachmentFixture : AutoCloseable {
    private val appConfig = postgresAppConfig(newPostgresSchema("attachments"))
    val dataSource = PostgresDataSourceFactory.create(appConfig.postgres)
    val clock = AttachmentClock()
    private val storageId = UUID.randomUUID().toString()
    val config = AttachmentStorageConfig(
        endpoint = "http://${AttachmentMinio.server.host}:${AttachmentMinio.server.firstMappedPort}",
        bucket = "attachments-$storageId",
        accessKey = "user-$storageId", secretKey = "attachment-user-secret", maxFileBytes = 1024,
    )
    init { AttachmentMinio.provision(config) }

    val di = DI {
        import(backendDiModule("Attachment test", appConfig.copy(attachments = config), dataSourceFactory = { dataSource }))
        bindSingleton<Clock>(overrides = true) { clock }
    }.direct
    val storage: AttachmentStorage = di.instance()
    val objects: S3AttachmentObjects = di.instance()
    suspend fun save(user: String, key: String, name: String, text: String) = storage.save(user, key, name, text.byteInputStream())
    suspend fun execute(sql: String) = dataSource.write { it.createStatement().use { statement -> statement.execute(sql) } }
    override fun close() { objects.close(); dataSource.close() }
}
