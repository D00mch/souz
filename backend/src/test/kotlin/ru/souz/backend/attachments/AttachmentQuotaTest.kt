package ru.souz.backend.attachments

import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import ru.souz.backend.storage.postgres.PostgresAttachmentRepository

class AttachmentQuotaTest {
    @Test
    fun `owner and global bytes and counts reject before reading and persist across restart`() = attachmentTest {
        for (byteLimit in listOf(false, true)) {
            val limited = limitedStorage(byteLimit)
            suspend fun capture(owner: String, key: String) = limited.saveClientImage(owner, key, attachmentPng.size, "image/png") { attachmentPng }
            val first = capture("alice", "first")
            assertAttachmentError("attachment_quota_exceeded") {
                limited.saveClientImage("alice", "second", attachmentPng.size, null) { fail("Quota must precede downloading or decoding") }
            }
            val second = capture("bob", "second")
            // A new service and repository see the same durable quota, even for a fresh public UUID.
            val restarted = limitedStorage(byteLimit)
            assertAttachmentError("attachment_quota_exceeded") {
                restarted.saveClientImage(UUID.randomUUID().toString(), "third", attachmentPng.size, null) { fail("Global quota must precede source IO") }
            }
            assertEquals(first, restarted.saveClientImage("alice", "first", attachmentPng.size, null) { fail("Retry must reuse the stored file") })
            assertEquals(second, restarted.get("bob", second.id))
            assertAttachmentError("attachment_quota_exceeded") { limited.save("alice", "ordinary", "a.txt", "x".byteInputStream()) }
            val external = AttachmentMinio.upload(config, "alice", "external.txt", "x".toByteArray())
            assertAttachmentError("attachment_quota_exceeded") { limited.importUpload("alice", external.objectKey, external.name) }
            assertNull(repository.getByObjectKey("alice", external.objectKey), "Rejected imports must not grow metadata")
            assertEquals(first, limited.importUpload("alice", first.objectKey, first.name), "Owned reuse needs no new allowance")
            clock.now = first.expiresAt!!
            assertAttachmentError("attachment_quota_exceeded") { capture("carol", "before-cleanup") }
            assertEquals(2, storage.cleanup())
            assertNotEquals(first.id, capture("alice", "first").id)
            clock.now = clock.now.plus(ATTACHMENT_RETENTION)
            assertEquals(1, storage.cleanup())
        }
    }

    @Test
    fun `concurrent replicas reserve the global allowance before either downloads`() = attachmentTest {
        withTimeout(15_000) { coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val firstStorage = limitedStorage(byteLimit = true, ownerBytes = 2L * attachmentPng.size,
                totalBytes = attachmentPng.size.toLong())
            val first = async { firstStorage.saveClientImage("alice", "first", attachmentPng.size, null) {
                entered.complete(Unit)
                release.await()
                attachmentPng
            } }
            try {
                entered.await()
                val secondStorage = limitedStorage(byteLimit = true, totalBytes = attachmentPng.size.toLong())
                val competing = List(6) { index -> async {
                    assertAttachmentError("attachment_quota_exceeded") {
                        secondStorage.saveClientImage("owner-$index", "competing", attachmentPng.size, null) { fail("Reserved capacity cannot be downloaded twice") }
                    }
                } }
                competing.forEach { it.await() }
            } finally { release.complete(Unit) }
            assertEquals(attachmentPng.size.toLong(), first.await().sizeBytes)
            clock.now = clock.now.plus(ATTACHMENT_RETENTION)
            assertEquals(1, storage.cleanup())
            val start = CompletableDeferred<Unit>()
            val racing = List(6) { index -> async {
                start.await()
                try {
                    Result.success(limitedStorage(byteLimit = false, totalFiles = 1)
                        .saveClientImage("racing-$index", "new", attachmentPng.size, null) { attachmentPng })
                } catch (error: AttachmentException) {
                    Result.failure<StoredAttachment>(error)
                }
            } }
            start.complete(Unit)
            val outcomes = racing.map { it.await() }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(List(5) { "attachment_quota_exceeded" }, outcomes.mapNotNull { (it.exceptionOrNull() as? AttachmentException)?.code })
        } }
    }

    @Test
    fun `validated bytes replace the maximum reservation before another source is admitted`() = attachmentTest {
        val limited = limitedStorage(byteLimit = true, ownerBytes = 2L * attachmentPng.size,
            maxFileBytes = 2 * attachmentPng.size)
        val first = limited.saveClientImage("alice", "first", 2 * attachmentPng.size, null) { bound ->
            assertEquals(2 * attachmentPng.size, bound)
            attachmentPng
        }
        val second = limited.saveClientImage("alice", "second", attachmentPng.size, null) { attachmentPng }
        assertEquals(first.sizeBytes, second.sizeBytes)
        assertAttachmentError("attachment_quota_exceeded") {
            limited.saveClientImage("alice", "third", 1, null) { fail("Actual source sizes fill the byte quota") }
        }
    }

    @Test
    fun `cancelled and invalid preparations stay accounted until pending cleanup`() = attachmentTest {
        val limited = limitedStorage(byteLimit = false, totalFiles = 1)
        withTimeout(15_000) { coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                limited.saveClientImage("alice", "cancelled", attachmentPng.size, null) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            cancelled.cancelAndJoin()
        } }
        assertAttachmentError("attachment_not_ready") {
            limited.saveClientImage("alice", "cancelled", attachmentPng.size, null) { fail("Pending attempts cannot restart") }
        }
        assertAttachmentError("attachment_quota_exceeded") {
            limited.saveClientImage("bob", "blocked", attachmentPng.size, null) { fail("Cancelled reservation remains accounted") }
        }
        clock.now = clock.now.plus(ATTACHMENT_PENDING_LIFETIME)
        assertEquals(1, storage.cleanup())
        assertFailsWith<IllegalArgumentException> {
            limited.saveClientImage("alice", "invalid", attachmentPng.size, null) { byteArrayOf(1) }
        }
        assertAttachmentError("attachment_quota_exceeded") {
            limited.saveClientImage("bob", "blocked", attachmentPng.size, null) { fail("Invalid reservation remains accounted") }
        }
        clock.now = clock.now.plus(ATTACHMENT_PENDING_LIFETIME)
        assertEquals(1, storage.cleanup())
        assertEquals(attachmentPng.size.toLong(), limited.saveClientImage("bob", "allowed", attachmentPng.size, null) { attachmentPng }.sizeBytes)
    }
}

private fun AttachmentFixture.limitedStorage(byteLimit: Boolean, ownerBytes: Long = attachmentPng.size.toLong(),
    totalBytes: Long = 2L * attachmentPng.size, totalFiles: Int = 2,
    maxFileBytes: Int = attachmentPng.size): AttachmentStorage = AttachmentStorage(
    AttachmentStorageConfig(config.endpoint, config.bucket, config.accessKey, config.secretKey,
        maxFileBytes = maxFileBytes, maxOwnerBytes = if (byteLimit) ownerBytes else Long.MAX_VALUE,
        maxTotalBytes = if (byteLimit) totalBytes else Long.MAX_VALUE,
        maxOwnerFiles = if (byteLimit) 100 else 1, maxTotalFiles = if (byteLimit) 100 else totalFiles),
    PostgresAttachmentRepository(dataSource), objects, clock,
)
