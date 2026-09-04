package ru.souz.agent.nodes

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import ru.souz.agent.ActiveRunInput
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.Node
import ru.souz.agent.runtime.ActiveRunInputController
import ru.souz.agent.runtime.ActiveRunInputController.NextLlmStep
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.toMessage

/** Main steerable chat node that owns each cancellable LLM attempt and replans around queued input. */
internal class SteerableChat(
    private val nodesLLM: NodesLLM,
    private val controller: ActiveRunInputController,
) : Node<String, LLMResponse.Chat> {
    override val name: String = "LLM"

    override suspend fun execute(
        ctx: AgentContext<String>,
        runtime: GraphRuntime,
    ): AgentContext<LLMResponse.Chat> {
        var current = ctx

        while (true) {
            when (val next = controller.nextLlmStep()) {
                is NextLlmStep.QueuedInput -> {
                    current = current.appendInputs(next.inputs)
                }

                is NextLlmStep.Request -> {
                    when (val attempt = runLlmAttempt(current, runtime, next)) {
                        is LlmAttempt.Replan -> current = current.appendInputs(attempt.queuedInputs)
                        is LlmAttempt.Completed -> {
                            val responseContext = attempt.context
                            val response = responseContext.input
                            val queuedInputs = if (response is LLMResponse.Chat.Ok && response.isToolUse) {
                                // An empty drain accepts this tool batch. Later input waits for its results.
                                controller.drain()
                            } else {
                                controller.drainOrSeal()
                            }

                            if (queuedInputs != null) {
                                current = responseContext.appendInputs(queuedInputs)
                                continue
                            }

                            return if (response is LLMResponse.Chat.Ok) {
                                responseContext.copy(
                                    history = responseContext.history + response.choices.mapNotNull { it.toMessage() },
                                )
                            } else {
                                responseContext
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun runLlmAttempt(
        context: AgentContext<String>,
        runtime: GraphRuntime,
        request: NextLlmStep.Request,
    ): LlmAttempt = supervisorScope {
        val llm = async(start = CoroutineStart.LAZY) {
            nodesLLM.provisionalChat("LLM request", request.streamRevision).execute(context, runtime)
        }

        if (request.inputAvailable.isCompleted) {
            llm.cancelAndJoin()
            return@supervisorScope LlmAttempt.Replan(controller.requireQueuedInputs())
        }

        llm.start()
        select<LlmAttempt> {
            // Completion wins when both clauses are ready, so provider cancellation always propagates.
            llm.onAwait { LlmAttempt.Completed(it) }
            request.inputAvailable.onAwait {
                if (!llm.isActive) {
                    LlmAttempt.Completed(llm.await())
                } else {
                    llm.cancelAndJoin()
                    LlmAttempt.Replan(controller.requireQueuedInputs())
                }
            }
        }
    }

    private suspend fun ActiveRunInputController.requireQueuedInputs(): List<ActiveRunInput> =
        checkNotNull(drain()) { "An input notification must have queued user input" }

    private val LLMResponse.Chat.Ok.isToolUse: Boolean
        get() = choices.any { it.message.functionCall != null }

    private fun AgentContext<*>.appendInputs(inputs: List<ActiveRunInput>): AgentContext<String> {
        require(inputs.isNotEmpty()) { "Queued input is required" }
        val nextHistory = buildList {
            addAll(history)
            inputs.forEach { input ->
                addAll(input.history)
                add(LLMRequest.Message(LLMMessageRole.user, input.input))
            }
        }
        return map(history = nextHistory) { inputs.last().input }
    }

    private sealed interface LlmAttempt {
        data class Completed(val context: AgentContext<LLMResponse.Chat>) : LlmAttempt
        data class Replan(val queuedInputs: List<ActiveRunInput>) : LlmAttempt
    }
}
