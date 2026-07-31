package ru.souz

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.GraphStepCallback
import ru.souz.agent.TraceableAgent
import ru.souz.agent.graph.Graph
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.Node
import ru.souz.agent.graph.buildGraph
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSkillInventory
import ru.souz.agent.nodes.NodesToolUseWithKnowledge
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.SKILL_INVENTORY_NODE_NAME
import ru.souz.agent.runtime.ActiveRunInputController
import ru.souz.agent.runtime.ActiveRunInputController.LlmRunResult
import ru.souz.agent.runtime.GraphExecutionDelegate
import ru.souz.agent.runtime.GraphExecutionDelegateImpl
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.toMessage

/**
 * Agent graph whose model always sees only the core skill-discovery, Knowledge, and execution tools.
 * Other capabilities are discovered and invoked through skills rather than injected directly.
 */
class SkillsGraphBasedAgent internal constructor(
    logObjectMapper: ObjectMapper,
    private val nodesLLM: NodesLLM,
    private val nodesCommon: NodesCommon,
    private val nodesErrorHandling: NodesErrorHandling,
    private val nodesSummarization: NodesSummarization,
    private val nodesMemory: NodesMemory,
    private val nodesSkillInventory: NodesSkillInventory,
    private val nodesToolUseWithKnowledge: NodesToolUseWithKnowledge,
    getSkillByNameTool: LLMToolSetup,
    getSkillsByCategoryTool: LLMToolSetup,
    getSkillsNamesByCategoryTool: LLMToolSetup,
    getKnowledgeTool: LLMToolSetup,
    searchKnowledgeTool: LLMToolSetup,
    runtimeCommandTool: LLMToolSetup,
    private val executionDelegate: GraphExecutionDelegate = GraphExecutionDelegateImpl(
        logObjectMapper = logObjectMapper,
        loggerClass = SkillsGraphBasedAgent::class.java,
    ),
) : TraceableAgent {
    override val sideEffects: Flow<String> = nodesLLM.sideEffects

    private val alwaysInlineResultTools = listOf(
        getSkillByNameTool,
        getSkillsByCategoryTool,
        getSkillsNamesByCategoryTool,
        getKnowledgeTool,
        searchKnowledgeTool,
    )
    private val coreTools = alwaysInlineResultTools + runtimeCommandTool
    private val activeRun = MutableStateFlow<ActiveRunInputController?>(null)

    private fun graph(controller: ActiveRunInputController): Graph<String, String> = buildGraph(name = "Skills Agent") {
        val inputToHistory = nodesCommon.inputToHistory()
        val memoryRecall = nodesMemory.recall()
        val skillInventory = nodesSkillInventory.node(
            skillTools = emptyList(),
            name = SKILL_INVENTORY_NODE_NAME,
        )
        val contextEnrich = nodesCommon.nodeAppendAdditionalData()
        val provisionalChat = nodesLLM.provisionalChat("LLM request")
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
        val toolUse = nodesToolUseWithKnowledge.node(
            alwaysInlineToolNames = alwaysInlineResultTools.mapTo(mutableSetOf()) { it.fn.name },
        )
        val queuedInputAfterTools: Node<String, String> = Node("Queued input after tools") { ctx ->
            val queuedInput = controller.drain()
            if (queuedInput == null) ctx else ctx.appendUserInput(queuedInput) { queuedInput }
        }
        val finalizeTurn = nodesMemory.finalizeTurn(
            summarization = nodesSummarization.summarize(),
        )
        val chatErrorToFinish = nodesErrorHandling.chatErrorToFinish()

        nodeInput.edgeTo(inputToHistory)
        inputToHistory.edgeTo(memoryRecall)
        memoryRecall.edgeTo(skillInventory)
        skillInventory.edgeTo(contextEnrich)
        contextEnrich.edgeTo(chat)
        chat.edgeTo(processChat)
        processChat.edgeTo { ctx ->
            when (ctx.input) {
                ChatRoute.Replan -> replan
                is ChatRoute.Tool -> toolResponse
                is ChatRoute.Final -> finalResponse
                is ChatRoute.Error -> errorResponse
            }
        }
        replan.edgeTo(chat)
        toolResponse.edgeTo(toolUse)
        toolUse.edgeTo(queuedInputAfterTools)
        queuedInputAfterTools.edgeTo(chat)
        finalResponse.edgeTo(finalizeTurn)
        errorResponse.edgeTo(chatErrorToFinish)
        finalizeTurn.edgeTo(nodeFinish)
        chatErrorToFinish.edgeTo(nodeFinish)
    }

    override fun cancelActiveJob() {
        activeRun.value?.close()
        executionDelegate.cancelActiveJob()
    }

    override suspend fun submitToActiveRun(input: String): Boolean =
        activeRun.value?.submit(input) ?: false

    override suspend fun execute(ctx: AgentContext<String>): String =
        executeWithTrace(ctx).output

    override suspend fun executeWithTrace(
        ctx: AgentContext<String>,
        onStep: GraphStepCallback?,
    ): AgentExecutionResult {
        val restrictedContext = nodesSkillInventory.restrictToTools(ctx, coreTools)
        val controller = ActiveRunInputController()
        val executionGraph = graph(controller)
        activeRun.value = controller
        return try {
            executionDelegate.executeWithTrace(graph = executionGraph, ctx = restrictedContext, onStep = onStep)
        } finally {
            controller.close()
            activeRun.compareAndSet(controller, null)
        }
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

    private sealed interface ChatAttempt {
        data object Replan : ChatAttempt
        data class Completed(val response: LLMResponse.Chat) : ChatAttempt
    }

    private sealed interface ChatRoute {
        data object Replan : ChatRoute
        data class Tool(val response: LLMResponse.Chat.Ok) : ChatRoute
        data class Final(val response: LLMResponse.Chat.Ok) : ChatRoute
        data class Error(val response: LLMResponse.Chat.Error) : ChatRoute
    }
}
