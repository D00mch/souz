package ru.souz.backend.attachments

import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals

class AttachmentImportTest {
    @Test
    fun `opaque keys import with independent UUIDs and remain the storage address through expiry cleanup`() = attachmentTest {
        val key = "custom incoming/скриншот-final.png"
        val bytes = "# Object without a UUID in its path".toByteArray()
        val upload = AttachmentMinio.upload(config, "alice", "report.md", bytes, objectKey = key)
        val files = coroutineScope {
            List(3) { async { storage.importUpload("alice", key, "report.md") } }.map { it.await() }
        }
        val saved = files.first()
        assertEquals(listOf(saved, saved, saved), files)
        assertNotEquals(upload.id, saved.id)
        assertEquals(key, saved.objectKey)
        assertContentEquals(bytes, storage.read("alice", saved.id))
        assertAttachmentError("attachment_not_found") { storage.importUpload("bob", key, "report.md") }
        assertAttachmentError("attachment_idempotency_conflict") { storage.importUpload("alice", key, "renamed.md") }
        clock.now = saved.expiresAt!!
        assertEquals(1, storage.cleanup())
        assertNull(repository.get("alice", saved.id))
        assertAttachmentError("attachment_not_found") { storage.importUpload("alice", key, "report.md") }
    }

    @Test
    fun `imports reject missing oversized and foreign objects without replacing owned metadata`() = attachmentTest {
        val id = UUID.randomUUID()
        val key = attachmentObjectKey("alice", id)
        assertAttachmentError("attachment_not_found") { storage.importUpload("alice", key, "missing.txt") }
        assertNull(repository.getByObjectKey("alice", key))
        AttachmentMinio.upload(config, "alice", "large.bin", ByteArray(config.maxFileBytes + 1), id)
        assertAttachmentError("attachment_too_large") { storage.importUpload("alice", key, "large.bin") }
        assertNull(repository.getByObjectKey("alice", key))
        val original = save("alice", "existing", "a.txt", "original")
        assertAttachmentError("attachment_not_found") { storage.importUpload("bob", original.objectKey, original.name) }
        assertEquals(original, storage.get("alice", original.id))
        assertNull(repository.get("bob", original.id))
        assertContentEquals("original".toByteArray(), storage.read("alice", original.id))
    }
}
