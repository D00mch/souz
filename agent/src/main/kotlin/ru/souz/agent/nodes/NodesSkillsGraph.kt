package ru.souz.agent.nodes

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Node
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.llms.toSystemPromptMessage
import ru.souz.tool.DEFAULT_STORED_SKILLS_CATEGORY

/** Nodes and execution-boundary context preparation owned by [ru.souz.SkillsGraphBasedAgent]. */
internal class NodesSkillsGraph(
    private val nodesCommon: NodesCommon,
    private val knowledgeStore: ConversationKnowledgeStore?,
    toolCatalog: AgentToolCatalog,
) {
    private val l = LoggerFactory.getLogger(NodesSkillsGraph::class.java)
    private val promptAugmenter = SkillsPromptAugmenter(toolCatalog)

    fun prepareContext(
        ctx: AgentContext<String>,
        coreTools: List<LLMToolSetup>,
    ): AgentContext<String> = ctx.copy(
        settings = ctx.settings.copy(
            tools = AgentTools(
                byCategory = emptyMap(),
                byName = coreTools.associateBy { it.fn.name },
            )
        ),
        activeTools = coreTools.map { it.fn },
        history = promptAugmenter.augment(ctx.systemPrompt, ctx.history),
    )

    /** Executes tool calls and replaces oversized results with conversation-scoped Knowledge references. */
    fun toolUseWithKnowledge(
        knowledgeToolNames: Set<String>,
        name: String = "toolUse",
    ): Node<LLMResponse.Chat.Ok, String> = Node(name) { ctx ->
        val fnCallMessages = nodesCommon.executeFunctionCalls(ctx).map { (functionCall, message) ->
            if (
                functionCall.name in knowledgeToolNames ||
                message.content.toByteArray(Charsets.UTF_8).size <= KNOWLEDGE_OFFLOAD_THRESHOLD_BYTES
            ) {
                message
            } else {
                offloadToolResult(
                    message = message,
                    sourceTool = functionCall.name,
                    meta = ctx.toolInvocationMeta,
                )
            }
        }
        val history = ArrayList(ctx.history).apply { addAll(fnCallMessages) }
        ctx.map(history = history) { ctx.history.last().content }
    }

    private suspend fun offloadToolResult(
        message: LLMRequest.Message,
        sourceTool: String,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        val store = knowledgeStore ?: run {
            l.warn("Knowledge storage is unavailable; keeping oversized {} result inline", sourceTool)
            return message
        }
        return try {
            when (val writeResult = store.put(meta, sourceTool, message.content)) {
                KnowledgeWriteResult.ConversationUnavailable -> {
                    l.warn("Conversation scope is unavailable; keeping oversized {} result inline", sourceTool)
                    message
                }

                is KnowledgeWriteResult.Stored -> {
                    val entry = writeResult.entry
                    message.copy(
                        content = restJsonMapper.writeValueAsString(
                            linkedMapOf(
                                "knowledgeId" to entry.id,
                                "sourceTool" to entry.sourceTool,
                                "originalLength" to entry.originalLength,
                                "storedLength" to entry.storedLength,
                                "truncated" to (entry.storedLength < entry.originalLength),
                                "instruction" to "Call GetKnowledge with this knowledgeId for all retained content, or SearchKnowledge for targeted regex retrieval.",
                            )
                        )
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            l.warn("Knowledge storage failed; keeping oversized {} result inline", sourceTool, error)
            message
        }
    }
}

private class SkillsPromptAugmenter(toolCatalog: AgentToolCatalog) {
    private val categoryBlock = buildString {
        append("<skill_categories>\nSkill category names:\n")
        append(toolCatalog.skillCategoryNames().joinToString(separator = "\n") { "- $it" })
        append("\n</skill_categories>")
    }

    fun augment(
        systemPrompt: String,
        history: List<LLMRequest.Message>,
    ): List<LLMRequest.Message> {
        val message = "$systemPrompt\n\n$categoryBlock".toSystemPromptMessage()
        if (history.isEmpty()) return listOf(message)
        return if (history.first().role == LLMMessageRole.system) {
            listOf(message) + history.drop(1)
        } else {
            listOf(message) + history
        }
    }
}

private fun AgentToolCatalog.skillCategoryNames(): List<String> = toolsByCategory
    .filterValues { it.isNotEmpty() }
    .keys
    .map { it.name }
    .plus(DEFAULT_STORED_SKILLS_CATEGORY)
    .distinct()
    .sorted()

private const val KNOWLEDGE_OFFLOAD_THRESHOLD_BYTES = 4_096
