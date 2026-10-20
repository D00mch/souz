package ru.souz.backend.attachments

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import ru.souz.backend.storage.postgres.PostgresAttachmentRepository
import software.amazon.awssdk.services.s3.model.S3Exception

class AttachmentStorageTest {
    @Test
    fun `private owner-scoped files survive restart and deduplicate until expiry`() = attachmentTest {
        val first = save("alice", "event", "../report.md", "# Привет")
        val second = save("bob", "event", "report.md", "# Привет")
        assertNotEquals(first.objectKey.substringBeforeLast('/'), second.objectKey.substringBeforeLast('/'))
        assertEquals("report.md", first.name)
        assertEquals("text/markdown", first.mimeType)
        assertAttachmentError("attachment_not_found") { storage.read("bob", first.id) }
        assertNull(storage.find("bob", "unknown"))
        val anonymous = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("${config.endpoint}/${config.bucket}/${first.objectKey}")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(403, anonymous.statusCode())
        val denied = AttachmentMinio.server.execInContainer("mc", "mb", "${config.accessKey}/forbidden-${config.bucket}")
        assertTrue(denied.exitCode != 0 && denied.stderr.contains("Access Denied", ignoreCase = true))
        val restarted = AttachmentStorage(config, PostgresAttachmentRepository(dataSource), objects, clock)
        assertEquals(first, restarted.get("alice", first.id))
        assertEquals("# Привет", restarted.read("alice", first.id).decodeToString())
        assertEquals(second, restarted.find("bob", "event"))
        clock.now = first.expiresAt!!.minusSeconds(1)
        coroutineScope {
            List(2) { async { save("alice", "event", "report.md", "# Привет") } }
                .forEach { assertEquals(first, it.await()) }
        }
        for ((name, content) in listOf("other.md" to "# Привет", "report.md" to "different")) {
            assertAttachmentError("attachment_idempotency_conflict") { save("alice", "event", name, content) }
        }
        assertEquals(0, storage.cleanup())
        clock.now = first.expiresAt
        assertAttachmentError("attachment_expired") { storage.read("alice", first.id) }
        assertAttachmentError("attachment_expired") { save("alice", "event", "report.md", "# Привет") }
        assertEquals(2, storage.cleanup())
        assertMissingObject { objects.read(first) }
        assertNull(repository.get("alice", first.id))
        assertAttachmentError("attachment_not_found") { storage.get("alice", first.id) }
        assertNotEquals(first.id, save("alice", "event", "report.md", "# Привет").id)
        assertEquals(0, storage.cleanup())
    }

    @Test
    fun `content validation preserves safe names and verifies reads independently of upload limits`() = attachmentTest {
        for ((index, names) in listOf(
            "C:\\tmp\\report.txt" to "report.txt", "\u0000a\r\n.txt\u007f" to "a.txt",
            "  report.txt  " to "report.txt", "" to "attachment", " \t " to "attachment",
            "." to "attachment", ".." to "attachment", "a".repeat(181) to "a".repeat(180),
            "😀.txt" to "😀.txt", "a".repeat(178) + "😀.txt" to "a".repeat(178) + "😀",
            "a".repeat(179) + "😀.txt" to "a".repeat(179),
        ).withIndex()) {
            val file = save("alice", "name-$index", names.first, "content")
            assertEquals(names.second, storage.get("alice", file.id).name)
            assertEquals(file, save("alice", "name-$index", names.first, "content"))
        }
        val bytes = ByteArray(config.maxFileBytes) { it.toByte() }
        val file = storage.save("alice", "binary", "fake.md", bytes.inputStream())
        assertNotEquals("text/markdown", file.mimeType)
        val smaller = AttachmentStorageConfig(config.endpoint, config.bucket, config.accessKey, config.secretKey, maxFileBytes = 500)
        val limited = AttachmentStorage(smaller, repository, objects, clock)
        assertContentEquals(bytes, limited.read("alice", file.id))
        assertAttachmentError("attachment_too_large") { limited.save("alice", "large", "a.bin", bytes.inputStream()) }
        val broken = object : InputStream() { override fun read(): Int = throw java.io.IOException("broken") }
        assertFailsWith<java.io.IOException> { storage.save("alice", "broken", "a.txt", broken) }
        for (replacement in listOf(bytes.copyOf().apply { this[0] = 1 }, bytes.copyOf(bytes.size - 1), bytes.copyOf(bytes.size + 1))) {
            objects.put(file, replacement)
            assertAttachmentError("attachment_corrupt") { storage.read("alice", file.id) }
        }
        objects.put(file, bytes)
        assertContentEquals(bytes, storage.read("alice", file.id))
        clock.now = clock.now.plus(ATTACHMENT_PENDING_LIFETIME)
        assertEquals(0, storage.cleanup()) // Invalid streams never reserve metadata.
    }

    @Test
    fun `failed completion waits for pending timeout and retries failed metadata deletion`() = attachmentTest {
        execute("""create function reject_attachment() returns trigger language plpgsql as 'begin raise exception ''test failure''; end'""")
        execute("""create constraint trigger reject_ready after update on stored_attachments deferrable initially deferred for each row when (new.state = 'ready') execute function reject_attachment()""")
        assertFailsWith<SQLException> { save("alice", "failed", "a.txt", "persisted") }
        execute("drop trigger reject_ready on stored_attachments")
        assertAttachmentError("attachment_not_ready") { save("alice", "failed", "a.txt", "persisted") }
        assertNull(storage.find("alice", "failed"))
        clock.now = clock.now.plus(ATTACHMENT_PENDING_LIFETIME).minusSeconds(1)
        assertEquals(0, storage.cleanup())
        clock.now = clock.now.plusSeconds(1)
        // Completion must fail at the deadline even before a worker has claimed the row.
        val pending = repository.reserve("alice", "stale", "a.txt", "text/plain", 0, "", clock.now.minus(ATTACHMENT_PENDING_LIFETIME))
        assertAttachmentError("attachment_expired") { repository.complete(pending, clock.now) }
        val uploaded = repository.expire(clock.now).single { it.id != pending.id }
        assertContentEquals("persisted".toByteArray(), objects.read(uploaded))
        execute("""create constraint trigger reject_delete after delete on stored_attachments deferrable initially deferred for each row execute function reject_attachment()""")
        assertEquals(0, storage.cleanup())
        assertMissingObject { objects.read(uploaded) }
        assertEquals("deleting", repository.get("alice", pending.id)?.state)
        assertAttachmentError("attachment_expired") { save("alice", "failed", "a.txt", "persisted") }
        execute("drop trigger reject_delete on stored_attachments")
        assertEquals(2, storage.cleanup())
        assertNull(repository.get("alice", pending.id))
        assertAttachmentError("attachment_expired") { repository.complete(pending, clock.now) }
        assertEquals("persisted", storage.read("alice", save("alice", "failed", "a.txt", "persisted").id).decodeToString())
    }

    @Test
    fun `missing bucket leaves a pending upload and object deletion failures are retryable with safe logs`() = attachmentTest {
        val missing = AttachmentStorageConfig(config.endpoint, "missing-${UUID.randomUUID()}", "attachment-test", "attachment-test-secret")
        val logger = LoggerFactory.getLogger(AttachmentStorage::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            S3AttachmentObjects(missing).use { unavailable ->
                val failed = AttachmentStorage(missing, repository, unavailable, clock)
                assertEquals(404, assertFailsWith<S3Exception> { failed.save("alice", "missing", "a.txt", "bytes".byteInputStream()) }.statusCode())
                assertEquals(0, failed.cleanup())
                clock.now = clock.now.plus(ATTACHMENT_PENDING_LIFETIME)
                assertEquals(0, failed.cleanup())
            }
            val failure = logs.list.single()
            assertTrue(failure.formattedMessage.contains("statusCode=404, s3ErrorCode=NoSuchBucket"))
            assertNull(failure.throwableProxy)
            for (secret in listOf(missing.endpoint, missing.bucket, missing.accessKey, missing.secretKey)) {
                assertFalse(failure.formattedMessage.contains(secret))
            }
            assertEquals(1, storage.cleanup())
            assertEquals(0, storage.cleanup())
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    @Test
    fun `one bounded queue handles ready pending and retry deadlines`() = attachmentTest {
        val expected = mutableSetOf<UUID>()
        val old = clock.now.minus(Duration.ofDays(31))
        repeat(3) { i ->
            expected += repository.reserve("alice", "retry-$i", "a", "text/plain", 0, "", old).id
        }
        assertEquals(expected, repository.expire(clock.now).map { it.id }.toSet())
        repeat(3) { i ->
            expected += repository.complete(repository.reserve("alice", "ready-$i", "a", "text/plain", 0, "", old), old).id
            expected += repository.reserve("alice", "pending-$i", "a", "text/plain", 0, "", old).id
        }
        val fresh = repository.reserve("alice", "fresh", "a", "text/plain", 0, "", clock.now)
        repository.complete(fresh, clock.now)
        repository.reserve("alice", "pending", "a", "text/plain", 0, "", clock.now)
        for (size in listOf(4, 4, 1)) {
            val batch = repository.expire(clock.now, 4)
            assertEquals(size, batch.size)
            for (file in batch) {
                assertEquals("deleting", file.state)
                assertTrue(expected.remove(file.id))
                repository.remove(file)
            }
        }
        assertTrue(expected.isEmpty())
        assertEquals(0, storage.cleanup())
    }

    @Test
    fun `reads share upload slots and cancellation releases the permit`() = attachmentTest(maxConcurrentUploads = 1) {
        val file = save("alice", "readable", "a.txt", "content")
        withTimeout(10_000) {
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val input = object : InputStream() {
                    override fun read(): Int { entered.complete(Unit); release.await(); return -1 }
                }
                val upload = async { storage.save("alice", "blocked", "empty.txt", input) }
                try {
                    entered.await()
                    val cancelled = async(start = CoroutineStart.UNDISPATCHED) { storage.read("alice", file.id) }
                    assertFalse(cancelled.isCompleted)
                    cancelled.cancelAndJoin()
                    val read = async(start = CoroutineStart.UNDISPATCHED) { storage.read("alice", file.id) }
                    assertFalse(read.isCompleted)
                    upload.cancelAndJoin()
                    assertEquals("content", read.await().decodeToString())
                } finally {
                    release.countDown()
                }
            }
        }
    }
}

private suspend fun assertAttachmentError(code: String, block: suspend () -> Unit) {
    assertEquals(code, assertFailsWith<AttachmentException> { block() }.code)
}

private suspend fun assertMissingObject(block: suspend () -> Unit) {
    val error = assertFailsWith<S3Exception> { block() }
    assertEquals(404, error.statusCode())
    assertEquals("NoSuchKey", error.awsErrorDetails().errorCode())
}
