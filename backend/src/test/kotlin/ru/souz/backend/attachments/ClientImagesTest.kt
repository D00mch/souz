package ru.souz.backend.attachments

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import java.util.Base64
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import ru.souz.backend.client.ClientImages
import ru.souz.backend.client.ClientToolOutcome
import ru.souz.llms.FilePresentation
import ru.souz.llms.restJsonMapper

class ClientImagesTest {
    @Test
    fun `result limits precede storage access and repeated IDs share metadata and image validation`() = runTest {
        val id = UUID.randomUUID()
        val storage = mockk<AttachmentStorage>()
        val file = mockk<StoredAttachment>()
        coEvery { storage.getAll("owner", setOf(id)) } returns mapOf(id to file)
        coEvery { storage.read(file) } returns attachmentPng
        every { file.id } returns id
        every { file.mimeType } returns "image/png"
        every { file.sizeBytes } returns attachmentPng.size.toLong()
        every { file.name } returns "image.png"
        fun result(content: List<Map<String, String>>) =
            ClientToolOutcome("succeeded", restJsonMapper.valueToTree(mapOf("content" to content)), null)
        val link = mapOf("type" to "resource_link", "uri" to "attachment:$id")
        ClientImages(storage).use { images ->
            for (content in listOf(List(65) { mapOf("type" to "text") }, List(17) { link })) {
                assertEquals("client_tool_failed", images.store("owner", "large", result(content)).error?.code)
            }
            confirmVerified(storage)
            val allowed = images.store("owner", "allowed", result(List(5) { link }))
            assertEquals("succeeded", allowed.status)
            assertEquals(List(5) { id.toString() }, allowed.files.map { it.fileId })
            coVerify(exactly = 1) { storage.getAll("owner", setOf(id)) }
            coVerify(exactly = 1) { storage.read(file) }
        }
    }

    @Test
    fun `normalization validates sources and trusts owned metadata only`() = attachmentTest {
        val user = "owner"
        val encoded = Base64.getEncoder().encodeToString(attachmentPng)
        val saved = storage.save(user, "image", "image.png", attachmentPng.inputStream()).toContent(FilePresentation.image)
        val broken = storage.save(user, "broken", "broken.png", attachmentPng.copyOf(33).inputStream())
        val document = storage.save(user, "report", "report.txt", "A report".byteInputStream())
        val forged = saved.copy(fileId = "forged")
        fun outcome(content: Any) = ClientToolOutcome("succeeded", restJsonMapper.valueToTree(mapOf("content" to content)), null)
        fun link(uri: String, mime: String = "image/png") = mapOf("type" to "resource_link", "uri" to uri,
            "mimeType" to mime, "attachment" to forged)
        ClientImages(storage).use { images ->
            for (embedded in listOf(false, true)) {
                fun image(data: String, mime: String = "image/png") = if (embedded)
                    mapOf("type" to "resource", "resource" to mapOf("uri" to "screen://capture", "mimeType" to mime, "blob" to data))
                else mapOf("type" to "image", "mimeType" to mime, "data" to data)
                val invalid = listOf(
                    listOf(link("http://127.0.0.1/private.png")),
                    listOf(link("http://[64:ff9b::7f00:1]/private.png")),
                    listOf(link("http://127.0.0.1/capture", "IMAGE/PNG")),
                    listOf(image(encoded, "image/jpeg")),
                    listOf(image(encoded, "IMAGE/JPEG")),
                    listOf(image("invalid-base64")),
                    listOf(image(Base64.getEncoder().encodeToString(attachmentPng.copyOf(33)))),
                    List(6) { image(encoded) },
                    List(6) { image(encoded, "Image/PnG") },
                    listOf(link("attachment:${saved.fileId}", "text/plain")),
                )
                for ((index, content) in invalid.withIndex()) {
                    val key = "invalid-$embedded-$index"
                    assertEquals("tool_image_unavailable", images.store("foreign", key, outcome(content)).error?.code)
                    assertNull(storage.find("foreign", "$key:0"), "Invalid screenshots must not be stored")
                }
                for (mime in listOf("image/png", "IMAGE/PNG", "Image/PnG")) {
                    val screenshot = images.store(user, "screenshot-$embedded-$mime", outcome(listOf(image(encoded, mime))))
                    assertEquals("succeeded", screenshot.status)
                    val image = screenshot.files.single()
                    assertContentEquals(attachmentPng, storage.read(user, UUID.fromString(image.fileId)))
                    assertEquals(restJsonMapper.valueToTree(mapOf("type" to "resource_link", "uri" to "attachment:${image.fileId}",
                        "name" to image.name, "mimeType" to "image/png")), screenshot.result!!["content"].single())
                }
            }
            for ((index, uri) in listOf("file:///screen.png", "screen://capture/0").withIndex()) {
                fun embedded(blob: String) = mapOf("type" to "resource", "resource" to mapOf("uri" to uri, "blob" to blob))
                val normalized = images.store(user, "untyped-$index", outcome(listOf(embedded(encoded))))
                assertEquals("succeeded", normalized.status)
                assertEquals("image/png", normalized.files.single().mimeType)
                assertFalse(encoded in normalized.result.toString())
                for (content in listOf(List(6) { embedded(encoded) }, listOf(embedded("ZG9jdW1lbnQ=")))) {
                    val rejected = images.store(user, "untyped-invalid-$index", outcome(content))
                    assertEquals("tool_image_unavailable", rejected.error?.code)
                    assertNull(rejected.result)
                }
            }
            assertEquals("tool_image_unavailable", images.store(user, "broken-link",
                outcome(listOf(link("attachment:${broken.id}")))).error?.code)
            val owned = images.store(user, "owned", outcome(listOf(link("attachment:${saved.fileId}", "text/plain"))))
            assertEquals(listOf(saved), owned.files)
            assertFalse(owned.result!!["content"].single().has("attachment"))
            val documents = images.store(user, "documents", outcome(List(6) { link("attachment:${document.id}") }))
            assertEquals("succeeded", documents.status)
            assertTrue(documents.files.isEmpty())
            assertEquals(6, documents.result!!["content"].size())
            assertTrue(documents.result["content"].all { it["mimeType"].asText() == "text/plain" && !it.has("attachment") })
            clock.now = clock.now.plus(ATTACHMENT_RETENTION)
            assertEquals("tool_image_unavailable", images.store(user, "expired", outcome(listOf(
                link("attachment:${document.id}"),
            ))).error?.code)
            val structured = images.store(user, "structured", outcome(mapOf("data" to link("attachment:forged"))))
            assertTrue(structured.files.isEmpty(), "Arbitrary structured data is not image content")
        }
    }
}
