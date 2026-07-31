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

/** Execution-scoped continuation nodes for the Skills graph. */
internal class ContinuationNodes(
    nodesLLM: NodesLLM,
    private val controller: ActiveRunInputController,
) {
    private val provisionalChat = nodesLLM.provisionalChat("LLM request")

    val chat: Node<String, ChatAttempt> = object : Node<String, ChatAttempt> {
        override val name: String = "LLM"

        override suspend fun execute(
            ctx: AgentContext<String>,
            runtime: GraphRuntime,
        ): AgentContext<ChatAttempt> = when (
            val result = controller.runInterruptibleLlm {
                provisionalChat.execute(ctx, runtime)
            }
        ) {
            is LlmRunResult.Completed -> result.value.map {
                ChatAttempt.Completed(result.value.input)
            }
            LlmRunResult.Replan -> ctx.map { ChatAttempt.Replan }
        }
    }

    val processChat: Node<ChatAttempt, ChatRoute> = Node("Process LLM response") { ctx ->
        when (val attempt = ctx.input) {
            ChatAttempt.Replan -> ctx.appendQueuedInput(controller.drain())
            is ChatAttempt.Completed -> when (val response = attempt.response) {
                is LLMResponse.Chat.Error -> {
                    val queuedInput = controller.drainOrSeal()
                    if (queuedInput != null) {
                        ctx.appendQueuedInput(queuedInput)
                    } else {
                        ctx.map { ChatRoute.Error(response) }
                    }
                }
                is LLMResponse.Chat.Ok -> if (response.isToolUse) {
                    // An empty drain is the atomic boundary after which the tool batch is treated as started.
                    val queuedInput = controller.drain()
                    if (queuedInput != null) {
                        ctx.appendQueuedInput(queuedInput)
                    } else {
                        ctx.commit(response) { ChatRoute.Tool(response) }
                    }
                } else {
                    val queuedInput = controller.drainOrSeal()
                    if (queuedInput != null) {
                        ctx.appendQueuedInput(queuedInput)
                    } else {
                        ctx.commit(response) { ChatRoute.Final(response) }
                    }
                }
            }
        }
    }

    val replan: Node<ChatRoute, String> = Node("Queued input -> LLM") { ctx ->
        ctx.map { ctx.history.lastOrNull()?.content.orEmpty() }
    }

    val toolResponse: Node<ChatRoute, LLMResponse.Chat.Ok> = Node("Accepted tool response") { ctx ->
        ctx.map { (ctx.input as ChatRoute.Tool).response }
    }

    val finalResponse: Node<ChatRoute, LLMResponse.Chat.Ok> = Node("Accepted final response") { ctx ->
        ctx.map { (ctx.input as ChatRoute.Final).response }
    }

    val errorResponse: Node<ChatRoute, LLMResponse.Chat> = Node("Accepted error response") { ctx ->
        ctx.map { (ctx.input as ChatRoute.Error).response }
    }

    val queuedInputAfterTools: Node<String, String> = Node("Queued input after tools") { ctx ->
        val queuedInput = controller.drain()
        if (queuedInput == null) ctx else ctx.appendUserInput(queuedInput) { queuedInput }
    }

    private val LLMResponse.Chat.Ok.isToolUse get() = choices.any { it.message.functionCall != null }

    private fun AgentContext<ChatAttempt>.appendQueuedInput(input: String?): AgentContext<ChatRoute> {
        checkNotNull(input) { "An LLM replan must have queued user input" }
        return appendUserInput(input) { ChatRoute.Replan }
    }

    private inline fun <I, reified O> AgentContext<I>.appendUserInput(
        input: String,
        transform: () -> O,
    ): AgentContext<O> = map(
        history = history + LLMRequest.Message(LLMMessageRole.user, input),
    ) { transform() }

    private inline fun AgentContext<ChatAttempt>.commit(
        response: LLMResponse.Chat.Ok,
        transform: () -> ChatRoute,
    ): AgentContext<ChatRoute> = map(
        history = history + response.choices.mapNotNull { it.toMessage() },
    ) { transform() }

    sealed interface ChatAttempt {
        data object Replan : ChatAttempt
        data class Completed(val response: LLMResponse.Chat) : ChatAttempt
    }

    sealed interface ChatRoute {
        data object Replan : ChatRoute
        data class Tool(val response: LLMResponse.Chat.Ok) : ChatRoute
        data class Final(val response: LLMResponse.Chat.Ok) : ChatRoute
        data class Error(val response: LLMResponse.Chat.Error) : ChatRoute
    }
}
