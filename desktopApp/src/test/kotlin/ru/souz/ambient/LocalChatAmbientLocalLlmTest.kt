package ru.souz.ambient

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalChatAmbientLocalLlmTest {

    @Test
    fun `ambient local request uses small completion budget`() = runTest {
        val api = CapturingChatApi()
        val llm = LocalChatAmbientLocalLlm(api)

        llm.complete(systemPrompt = "system", userPrompt = "user")

        assertEquals(96, api.lastRequest?.maxTokens)
        assertEquals("none", api.lastRequest?.functionCall)
        assertEquals(false, api.lastRequest?.stream)
    }

    @Test
    fun `ambient local request uses provided local model`() = runTest {
        val api = CapturingChatApi()
        val llm = LocalChatAmbientLocalLlm(api) { ru.souz.llms.LLMModel.LocalQwen3_4B_Instruct_2507 }

        llm.complete(systemPrompt = "system", userPrompt = "user")

        assertEquals("local-qwen3-4b-instruct-2507", api.lastRequest?.model)
    }

    private class CapturingChatApi : LLMChatAPI {
        var lastRequest: LLMRequest.Chat? = null

        override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
            lastRequest = body
            return LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = """{"type":"ambient_analysis","statements":[],"task_candidates":[]}""",
                            role = LLMMessageRole.assistant,
                            functionsStateId = null,
                        ),
                        index = 0,
                        finishReason = LLMResponse.FinishReason.stop,
                    )
                ),
                created = 1L,
                model = body.model,
                usage = LLMResponse.Usage(0, 0, 0, 0),
            )
        }

        override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
            error("not used")

        override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
            error("not used")

        override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
            error("not used")

        override suspend fun downloadFile(fileId: String): String? =
            error("not used")

        override suspend fun balance(): LLMResponse.Balance =
            error("not used")
    }
}
