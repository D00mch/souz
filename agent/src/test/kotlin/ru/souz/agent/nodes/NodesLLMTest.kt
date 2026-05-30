package ru.souz.agent.nodes

import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

class NodesLLMTest {
    @Test
    fun `streaming chat emits runtime deltas and keeps side effects batching`() = runTest {
        val runtimeEvents = mutableListOf<AgentRuntimeEvent>()
        val settingsProvider = mockk<AgentSettingsProvider> {
            every { useStreaming } returns true
        }
        val nodes = NodesLLM(
            llmApi = StreamingChatApi(
                chunks = listOf("Hello", " streaming", " world"),
                model = "test-model",
            ),
            settingsProvider = settingsProvider,
        )
        val context = AgentContext(
            input = "ignored",
            settings = AgentSettings(
                model = "test-model",
                temperature = 0.2f,
                toolsByCategory = emptyMap(),
            ),
            history = listOf(
                LLMRequest.Message(role = LLMMessageRole.user, content = "Prompt")
            ),
            activeTools = emptyList(),
            systemPrompt = "system",
            runtimeEventSink = object : AgentRuntimeEventSink {
                override suspend fun emit(event: AgentRuntimeEvent) {
                    runtimeEvents += event
                }
            },
        )

        val sideEffect = async { nodes.sideEffects.first() }
        val result = nodes.chat().execute(
            ctx = context,
            runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10),
        )

        assertEquals(
            listOf<AgentRuntimeEvent>(
                AgentRuntimeEvent.LlmMessageDelta("Hello"),
                AgentRuntimeEvent.LlmMessageDelta(" streaming"),
                AgentRuntimeEvent.LlmMessageDelta(" world"),
            ),
            runtimeEvents,
        )
        assertEquals("Hello streaming world", sideEffect.await())
        val response = result.input as LLMResponse.Chat.Ok
        assertEquals("Hello streaming world", response.choices.single().message.content)
        assertEquals("Hello streaming world", result.history.last().content)
    }
}

private class StreamingChatApi(
    private val chunks: List<String>,
    private val model: String,
) : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        error("Non-streaming path is not expected in this test.")

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        chunks.forEachIndexed { index, chunk ->
            emit(
                LLMResponse.Chat.Ok(
                    choices = listOf(
                        LLMResponse.Choice(
                            message = LLMResponse.Message(
                                content = chunk,
                                role = LLMMessageRole.assistant,
                                functionsStateId = null,
                            ),
                            index = 0,
                            finishReason = if (index == chunks.lastIndex) {
                                LLMResponse.FinishReason.stop
                            } else {
                                null
                            },
                        )
                    ),
                    created = index.toLong(),
                    model = model,
                    usage = LLMResponse.Usage(1, index + 1, index + 2, 0),
                )
            )
        }
    }

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in this test.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in this test.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in this test.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in this test.")
}
