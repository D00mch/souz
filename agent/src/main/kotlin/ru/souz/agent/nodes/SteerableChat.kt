package ru.souz.agent.nodes

import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.Node
import ru.souz.agent.runtime.ActiveRunInputController
import ru.souz.agent.runtime.ActiveRunInputController.LlmRunResult
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.toMessage

/** Main Skills chat node that replans around execution-scoped user input. */
internal class SteerableChat(
    nodesLLM: NodesLLM,
    private val controller: ActiveRunInputController,
) : Node<String, LLMResponse.Chat> {
    override val name: String = "LLM"

    private val provisionalChat = nodesLLM.provisionalChat("LLM request")

    override suspend fun execute(
        ctx: AgentContext<String>,
        runtime: GraphRuntime,
    ): AgentContext<LLMResponse.Chat> {
        var current = ctx

        while (true) {
            val attempt = controller.runInterruptibleLlm {
                provisionalChat.execute(current, runtime)
            }
            when (attempt) {
                is LlmRunResult.Replan -> {
                    current = current.appendUserInput(attempt.queuedInput)
                }

                is LlmRunResult.Completed -> {
                    val responseContext = attempt.value
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

    private val LLMResponse.Chat.Ok.isToolUse: Boolean
        get() = choices.any { it.message.functionCall != null }

    private fun AgentContext<*>.appendUserInput(input: String): AgentContext<String> = map(
        history = history + LLMRequest.Message(LLMMessageRole.user, input),
    ) { input }
}
