package ru.souz.llms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RequestBudgetTest {
    @Test
    fun `document upload IDs do not reserve image tokens while image transports still do`() {
        val request = LLMRequest.Chat(messages = listOf(LLMRequest.Message(LLMMessageRole.user, "Analyze documents")) +
            listOf("csv-id", "text-id", "pdf-id").map { id ->
                LLMRequest.Message(LLMMessageRole.function, id, name = "UploadFile", attachments = listOf(id))
            })
        val text = request.copy(messages = request.messages.map { it.copy(attachments = null) }).estimatedInputTokens()
        assertEquals(text, request.estimatedInputTokens())
        assertFalse(request.exceedsContextBudget(16_000))
        for (image in listOf("data:image/png;base64,a", "https://images.test/screen.png", "http://images.test/screen.png")) {
            val withImage = request.copy(messages = request.messages.mapIndexed { index, message ->
                if (index == 0) message.copy(attachments = listOf(image)) else message
            })
            assertEquals(text + IMAGE_INPUT_TOKEN_ALLOWANCE, withImage.estimatedInputTokens())
            assertEquals(text, withImage.copy(isSummarization = true).estimatedInputTokens())
        }
        val local = request.copy(provider = LlmProvider.LOCAL, messages = request.messages.mapIndexed { index, message ->
            message.copy(attachments = if (index == 0) listOf("/tmp/souz-vision-projection.png") else null)
        })
        assertEquals(text + IMAGE_INPUT_TOKEN_ALLOWANCE, local.estimatedInputTokens())
    }

    @Test
    fun `image allowance ignores duplicate references and encoded length and excludes summaries`() {
        val file = FileContent("image", "image.png", "image/png", 100)
        val request = LLMRequest.Chat(messages = listOf(LLMRequest.Message(LLMMessageRole.user, "inspect", files = List(6) { file })))
        val text = request.estimatedInputTokens(includeImageReferences = false)
        assertEquals(text + IMAGE_INPUT_TOKEN_ALLOWANCE, request.estimatedInputTokens())
        assertEquals(text, request.copy(isSummarization = true).estimatedInputTokens())
        for (encoded in listOf("data:image/png;base64,a", "data:image/png;base64," + "a".repeat(1_000_000))) {
            val projected = request.copy(messages = listOf(request.messages.single().copy(attachments = listOf(encoded))))
            assertEquals(text + IMAGE_INPUT_TOKEN_ALLOWANCE, projected.estimatedInputTokens(includeImageReferences = false))
        }
    }
}
