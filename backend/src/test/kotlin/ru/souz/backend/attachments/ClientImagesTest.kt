package ru.souz.backend.attachments

import java.util.Base64
import kotlin.test.*
import ru.souz.backend.client.ClientImages
import ru.souz.backend.client.ClientToolOutcome
import ru.souz.llms.FilePresentation
import ru.souz.llms.restJsonMapper

class ClientImagesTest {
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
        fun image(data: String, mime: String = "image/png") = mapOf("type" to "image", "mimeType" to mime, "data" to data)
        ClientImages(storage).use { images ->
            val invalid = listOf(
                listOf(link("http://127.0.0.1/private.png")),
                listOf(link("http://[64:ff9b::7f00:1]/private.png")),
                listOf(image(encoded, "image/jpeg")),
                listOf(image(Base64.getEncoder().encodeToString(attachmentPng.copyOf(33)))),
                List(6) { image(encoded) },
                listOf(link("attachment:${saved.fileId}", "text/plain")),
            )
            for (content in invalid) {
                assertEquals("tool_image_unavailable", images.store("foreign", "invalid", outcome(content)).error?.code)
                assertNull(storage.find("foreign", "invalid:0"), "Invalid screenshots must not be stored")
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
            val structured = images.store(user, "structured", outcome(mapOf("data" to link("attachment:forged"))))
            assertTrue(structured.files.isEmpty(), "Arbitrary structured data is not image content")
        }
    }
}
