package com.dumch.agent

import com.dumch.agent.node.NodesCommon.nodeRespToString
import com.dumch.agent.node.NodesCommon.nodeStringToReq
import com.dumch.agent.node.NodesCommon.nodeToolUse
import com.dumch.agent.node.NodesLLM
import com.dumch.db.DesktopInfoRepository
import com.dumch.db.VectorDB
import com.dumch.giga.*
import com.dumch.tool.ToolsFactory
import io.ktor.util.logging.debug
import org.slf4j.LoggerFactory

class GigaAgentGraph(
    llmApi: GigaChatAPI,
    private val nodeUserInput: Node<String, String> = Node("UserInput") { ctx ->
        println(ctx.input)
        ctx.map { readlnOrNull() ?: "..." }
    }
) {
    private val l = LoggerFactory.getLogger(GigaAgentGraph::class.java)
    private val llmNodes = NodesLLM(llmApi)

    fun buildAgent(): Engine {
        nodeUserInput.edgeTo(nodeStringToReq)
        nodeStringToReq.edgeTo(llmNodes.nodeCallMainLLM)
        llmNodes.nodeCallMainLLM.edgeTo { (input, _, _, _) ->
            when (input) {
                is GigaResponse.Chat.Error -> nodeRespToString
                is GigaResponse.Chat.Ok -> if (isToolUse(input)) nodeToolUse else nodeRespToString
            }.also { l.debug { "Get $input, route to node $it" } }
        }
        nodeToolUse.edgeTo(llmNodes.nodeCallMainLLM)
        nodeRespToString.edgeTo(nodeUserInput)
        return Engine(nodeUserInput)
    }

    private fun isToolUse(input: GigaResponse.Chat.Ok): Boolean = input.choices.any { it.message.functionCall != null }
}

fun <T> AgentContext<T>.toGigaRequest(history: List<GigaRequest.Message>): GigaRequest.Chat {
    val ctx = this
    return GigaRequest.Chat(
        model = ctx.settings.model,
        messages = history,
        functions = ctx.activeTools,
        temperature = ctx.settings.temperature,
    )
}

val SYSTEM_PROMPT = """
Ты — помощник, управляющий компьютером. Будь полезным. Говори только по существу.
Если получил команду, выполняй, потом говори, что сделал.
Если какую-то задачу можно решить c помощью имеющихся функций, сделай, а не проси пользователя сделать это. 
Если сомневаешься, уточни. 
Если работаешь с файлами, отвечай кратко, не нужно рассказывать все, только по делу.
""".trimIndent()

suspend fun main() {
    val api: GigaChatAPI = GigaRestChatAPI(GigaAuth)
    val desktopRepo = DesktopInfoRepository(GigaRestChatAPI(GigaAuth), VectorDB)
    val settings = AgentSettings(
        model = GigaModel.Pro.alias,
        temperature = 0.7f,
        toolsByCategory = ToolsFactory(desktopRepo).toolsByCategory
    )

    val seedContext = AgentContext(
        input = "Agent is ready, ask something:",
        settings = settings,
        history = emptyList(),
        activeTools = settings.tools.values.map { it.fn },
        systemPrompt = SYSTEM_PROMPT
    )
    val agent = GigaAgentGraph(api).buildAgent()
    agent.run(seedContext) { step, node, ctx ->
        println("Step: $step; node: $node; ctx class: ${ctx.input?.javaClass}, history size: ${ctx.history.size}")
    }
}

/*
TODO:
1. Compression
2. Stream
3. RAG
4. Classification
5. Coroutine friendly
 */