package com.dumch.agent.node

import com.dumch.agent.engine.AgentContext
import com.dumch.agent.engine.AgentSettings
import com.dumch.agent.engine.Node
import com.dumch.agent.toGigaRequest
import com.dumch.giga.GigaMessageRole
import com.dumch.giga.GigaRequest
import com.dumch.giga.GigaResponse
import com.dumch.giga.GigaToolSetup
import com.dumch.giga.toSystemPromptMessage
import org.slf4j.LoggerFactory

object NodesCommon {
    private val l = LoggerFactory.getLogger(NodesCommon::class.java)

    val stringToReq: Node<String, GigaRequest.Chat> = Node("String->Request") { ctx ->
        val usrMsg = GigaRequest.Message(GigaMessageRole.user, ctx.input)
        val history = ArrayList(ctx.history).apply {
            if (isEmpty()) add(ctx.systemPrompt.toSystemPromptMessage())
            add(usrMsg)
        }
        ctx.map(history = history) { ctx.toGigaRequest(history) }
    }

    val respToString: Node<GigaResponse.Chat, String> = Node("Response->String") { ctx ->
        when (val input = ctx.input) {
            is GigaResponse.Chat.Error -> ctx.map { input.message }
            is GigaResponse.Chat.Ok -> ctx.map { input.choices.last().message.content }
        }
    }

    val nodeToolUse: Node<GigaResponse.Chat, GigaRequest.Chat> = Node("toolUse") { ctx ->
        val fnCallMessages = fnCallMessages(ctx)
        val history = ArrayList(ctx.history).apply { addAll(fnCallMessages) }
        ctx.map(history = history) { ctx.toGigaRequest(history) }
    }

    private suspend fun fnCallMessages(ctx: AgentContext<GigaResponse.Chat>): List<GigaRequest.Message> {
        val fnCallMessages = (ctx.input as GigaResponse.Chat.Ok).choices.mapNotNull { choice ->
            val msg = choice.message
            if (msg.functionCall != null && msg.functionsStateId != null) {
                executeTool(ctx.settings, msg.functionCall)
            } else null
        }
        return fnCallMessages
    }

    private suspend fun executeTool(
        settings: AgentSettings,
        functionCall: GigaResponse.FunctionCall,
    ): GigaRequest.Message {
        val tools = settings.tools
        val fn: GigaToolSetup = tools[functionCall.name] ?: return GigaRequest.Message(
            GigaMessageRole.function, """{"result":"no such function ${functionCall.name}"}"""
        )
        l.info("Executing tool: ${fn.fn.name}, arguments: ${functionCall.arguments}")
        return fn.invoke(functionCall)
    }
}