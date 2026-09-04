package ru.souz.agent.nodes

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

/** Main steerable chat node that owns each cancellable LLM attempt and replans around queued input. */
internal class SteerableChatNode(
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
            val request = when (val step = controller.nextLlmStep()) {
                is NextLlmStep.QueuedInput -> {
                    current = current.appendInputs(step.inputs)
                    continue
                }

                is NextLlmStep.Request -> step
            }

            val responseContext = runLlmAttempt(current, runtime, request) ?: continue
            val response = responseContext.input
            val queuedInputs = if (response is LLMResponse.Chat.Ok && response.isToolUse) {
                // An empty drain accepts this tool batch. Later input waits for its results.
                controller.drain()
            } else {
                controller.drainOrSeal()
            }

            if (queuedInputs != null) {
                current = current.appendInputs(queuedInputs)
                continue
            }

            return responseContext
        }
    }

    private suspend fun runLlmAttempt(
        context: AgentContext<String>,
        runtime: GraphRuntime,
        request: NextLlmStep.Request,
    ): AgentContext<LLMResponse.Chat>? = supervisorScope {
        if (request.inputAvailable.isCompleted) return@supervisorScope null

        val llm = async {
            nodesLLM.chat("LLM request", request.streamRevision).execute(context, runtime)
        }

        select<AgentContext<LLMResponse.Chat>?> {
            // Completion wins when both clauses are ready, so provider cancellation always propagates.
            llm.onAwait { it }
            request.inputAvailable.onAwait {
                if (llm.isActive) {
                    llm.cancelAndJoin()
                    null
                } else {
                    llm.await()
                }
            }
        }
    }

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
}
