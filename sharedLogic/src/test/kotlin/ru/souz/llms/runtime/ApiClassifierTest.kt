package ru.souz.llms.runtime

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiClassifierTest {
    @Test
    fun `renders the conversation as one history block and the new message`() = runTest {
        val sent = slot<LLMRequest.Chat>()
        val api = mockk<LLMChatAPI> {
            coEvery { message(capture(sent)) } returns LLMResponse.Chat.Ok(
                listOf(LLMResponse.Choice(
                    LLMResponse.Message("MAIL,CALENDAR 90", LLMMessageRole.assistant, functionsStateId = null),
                    0, LLMResponse.FinishReason.stop,
                )),
                1, "model", LLMResponse.Usage(1, 2, 3, 0),
            )
        }
        val body = LLMRequest.Chat(
            model = "model", provider = LlmProvider.OPENAI,
            messages = listOf(
                LLMRequest.Message(LLMMessageRole.system, "Pick categories"),
                LLMRequest.Message(LLMMessageRole.user, " Prepare for today "),
                LLMRequest.Message(LLMMessageRole.assistant, "Two meetings"),
                LLMRequest.Message(LLMMessageRole.user, "Email the agenda"),
            ),
        )

        val reply = ApiClassifier(api).classify(body, emptyMap())

        assertEquals(listOf(ToolCategory.MAIL, ToolCategory.CALENDAR), reply.categories)
        assertEquals(90.0, reply.confidence)
        assertEquals(
            body.copy(messages = listOf(
                LLMRequest.Message(LLMMessageRole.system, "Pick categories"),
                LLMRequest.Message(LLMMessageRole.user, "History:\nUSER: Prepare for today\n\nASSISTANT: Two meetings\n"),
                LLMRequest.Message(LLMMessageRole.user, "New message:\nEmail the agenda"),
            )),
            sent.captured,
        )
    }
}
