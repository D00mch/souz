package ru.souz.agent.nodes

import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Node
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.toSystemPromptMessage

internal fun inputToHistoryNode(name: String = "Input->History"): Node<String, String> = Node(name) { ctx ->
    val history = ArrayList(ctx.history).apply {
        if (isEmpty()) add(ctx.systemPrompt.toSystemPromptMessage())
        add(LLMRequest.Message(LLMMessageRole.user, ctx.input))
    }
    ctx.map(history = history) { ctx.input }
}

internal fun responseToStringNode(
    name: String = "Response -> String",
): Node<LLMResponse.Chat.Ok, String> = Node(name) { ctx ->
    val content = ctx.input.choices
        .asReversed()
        .firstOrNull { it.message.content.isNotBlank() }
        ?.message
        ?.content
        ?: ctx.input.choices.lastOrNull()?.message?.content
        ?: run {
            LoggerFactory.getLogger("ru.souz.agent.nodes.NodesPlain").warn(
                "LLM returned no choices; using empty response. model={}, created={}",
                ctx.input.model,
                ctx.input.created,
            )
            ""
        }
    ctx.map { content }
}

internal fun toolUseNode(
    executor: AgentToolExecutor,
    name: String = "toolUse",
): Node<LLMResponse.Chat.Ok, String> = Node(name) { ctx ->
    val messages = executeFunctionCalls(ctx, executor).map { it.message }
    ctx.map(history = ctx.history + messages) { ctx.history.last().content }
}

internal suspend fun executeFunctionCalls(
    ctx: AgentContext<LLMResponse.Chat.Ok>,
    executor: AgentToolExecutor,
): List<ExecutedToolCall> = ctx.input.choices.mapNotNull { choice ->
    val message = choice.message
    val functionCall = message.functionCall
    val functionsStateId = message.functionsStateId
    if (functionCall != null && functionsStateId != null) {
        ExecutedToolCall(
            functionCall = functionCall,
            message = executor.execute(
                settings = ctx.settings,
                functionCall = functionCall,
                meta = ctx.toolInvocationMeta,
                toolCallId = functionsStateId,
                eventSink = ctx.runtimeEventSink,
            ).copy(functionsStateId = functionsStateId),
        )
    } else null
}

internal data class ExecutedToolCall(
    val functionCall: LLMResponse.FunctionCall,
    val message: LLMRequest.Message,
)
