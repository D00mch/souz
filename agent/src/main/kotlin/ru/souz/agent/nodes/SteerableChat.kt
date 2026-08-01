package ru.souz.agent.nodes

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.Node
import ru.souz.agent.runtime.ActiveRunInputController
import ru.souz.agent.runtime.ActiveRunInputController.NextLlmStep
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.toMessage

/** Main Skills chat node that owns each cancellable LLM attempt and replans around queued input. */
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
                    current = current.appendUserInput(next.input)
                }

                is NextLlmStep.Request -> {
                    when (val attempt = runLlmAttempt(current, runtime, next)) {
                        is LlmAttempt.Replan -> current = current.appendUserInput(attempt.queuedInput)
                        is LlmAttempt.Completed -> {
                            val responseContext = attempt.context
                            val response = responseContext.input
                            val queuedInput = if (response is LLMResponse.Chat.Ok && response.isToolUse) {
                                // An empty drain accepts this tool batch. Later input waits for its results.
                                controller.drain()
                            } else {
                                controller.drainOrSeal()
                            }

                            if (queuedInput != null) {
                                current = responseContext.appendUserInput(queuedInput)
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
            return@supervisorScope LlmAttempt.Replan(controller.requireQueuedInput())
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
                    LlmAttempt.Replan(controller.requireQueuedInput())
                }
            }
        }
    }

    private suspend fun ActiveRunInputController.requireQueuedInput(): String =
        checkNotNull(drain()) { "An input notification must have queued user input" }

    private val LLMResponse.Chat.Ok.isToolUse: Boolean
        get() = choices.any { it.message.functionCall != null }

    private fun AgentContext<*>.appendUserInput(input: String): AgentContext<String> = map(
        history = history + LLMRequest.Message(LLMMessageRole.user, input),
    ) { input }

    private sealed interface LlmAttempt {
        data class Completed(val context: AgentContext<LLMResponse.Chat>) : LlmAttempt
        data class Replan(val queuedInput: String) : LlmAttempt
    }
}
