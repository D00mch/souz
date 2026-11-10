package ru.souz.llms

import kotlin.test.Test
import kotlin.test.assertEquals

class RequestBudgetTest {
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
