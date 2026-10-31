package ru.souz.agent.scenarios

import kotlinx.coroutines.CancellationException
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.plus
import ru.souz.llms.restJsonMapper
import kotlin.test.assertTrue
import kotlin.time.Duration

data class ScenarioResult(
    val execution: AgentExecutionResult,
    val requests: List<LLMRequest.Chat>,
    val events: List<AgentRuntimeEvent>,
    val metrics: ScenarioMetrics,
)

data class ScenarioMetrics(
    val model: String,
    val reasoningEffort: String?,
    val modelCalls: Int,
    val usage: LLMResponse.Usage,
    val duration: Duration,
    val toolCalls: List<AgentRuntimeEvent.ToolCallStarted>,
) {
    override fun toString(): String =
        "model=$model; reasoningEffort=${reasoningEffort ?: "provider-default"}; " +
            "modelCalls=$modelCalls; durationMs=${duration.inWholeMilliseconds}; tokens=$usage; " +
            "tools=${restJsonMapper.writeValueAsString(toolCalls.map { mapOf("name" to it.name, "arguments" to it.arguments) })}"
}

/** The kernel makes model calls and emits events sequentially within one execution. */
internal class ScenarioTrace(
    private val modelId: String,
    private val reasoningEffort: String?,
) : AgentRuntimeEventSink {
    val requests = mutableListOf<LLMRequest.Chat>()
    val events = mutableListOf<AgentRuntimeEvent>()
    private var usage = LLMResponse.Usage(0, 0, 0, 0)
    private var lastResponse: LLMResponse.Chat? = null
    private val providerFailures = mutableListOf<String>()

    fun instrument(api: LLMChatAPI): LLMChatAPI = object : LLMChatAPI by api {
        override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
            check(requests.size < MAX_MODEL_CALLS) { "Scenario exceeded $MAX_MODEL_CALLS model calls without completing." }
            val request = body.copy(
                reasoningEffort = reasoningEffort,
            )
            requests += request
            val response = try {
                api.message(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                providerFailures += "Call ${requests.size}: $failure"
                throw failure
            }
            return response.also {
                lastResponse = response
                when (response) {
                    is LLMResponse.Chat.Ok -> usage += response.usage
                    is LLMResponse.Chat.Error -> providerFailures += "Call ${requests.size}: $response"
                }
            }
        }
    }

    override suspend fun emit(event: AgentRuntimeEvent) {
        events += event
    }

    fun assertCompleted(result: AgentExecutionResult) {
        assertTrue(providerFailures.isEmpty(), "Scenario provider failures: $providerFailures")
        val response = lastResponse
        assertTrue(response is LLMResponse.Chat.Ok, "No successful final model response: $response")
        assertTrue(response.choices.isNotEmpty() && response.choices.all {
            it.message.functionCall == null && it.finishReason == LLMResponse.FinishReason.stop
        }, "Scenario ended without a completed assistant response: ${response.choices}")
        assertTrue(result.output.isNotBlank(), "Scenario returned no final answer.")
        assertTrue(response.choices.any { it.message.content.trim() == result.output.trim() },
            "Output must come from the final model response.")
    }

    fun metrics(duration: Duration): ScenarioMetrics = ScenarioMetrics(
        modelId, reasoningEffort, requests.size, usage, duration,
        events.filterIsInstance<AgentRuntimeEvent.ToolCallStarted>(),
    )
}

private const val MAX_MODEL_CALLS = 24
