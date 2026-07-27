package ru.souz.agent.nodes

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Node
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.toSystemPromptMessage
import ru.souz.tool.ToolCategory

internal const val SKILL_INVENTORY_NODE_NAME = "Skill Inventory"

internal class NodesSkillInventory(
    private val toolCatalog: AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
    private val skillRegistryRepository: SkillRegistryRepository,
) {
    private val logger = LoggerFactory.getLogger(NodesSkillInventory::class.java)
    private val promptAugmenter = SkillInventoryPromptAugmenter()

    fun node(
        skillTools: List<LLMToolSetup>,
        name: String = SKILL_INVENTORY_NODE_NAME,
    ): Node<String, String> = Node(name) { ctx ->
        val inventory = loadInventory(ctx.toolInvocationMeta.userId)
        val skillToolsByName = skillTools.associateBy { it.fn.name }
        val updatedSettings = ctx.settings.copy(
            tools = ctx.settings.tools.copy(
                byName = ctx.settings.tools.byName + skillToolsByName,
            )
        )
        val updatedActiveTools = (ctx.activeTools + skillTools.map { it.fn })
            .distinctBy { it.name }
        ctx.map(
            settings = updatedSettings,
            activeTools = updatedActiveTools,
            history = promptAugmenter.augment(ctx.systemPrompt, ctx.history, inventory),
        ) { it }
    }

    fun restrictToTools(
        ctx: AgentContext<String>,
        tools: List<LLMToolSetup>,
    ): AgentContext<String> {
        val byName = tools.associateBy { it.fn.name }
        return ctx.copy(
            settings = ctx.settings.copy(
                tools = AgentTools(
                    byCategory = emptyMap(),
                    byName = byName,
                )
            ),
            activeTools = tools.map { it.fn },
        )
    }

    private suspend fun loadInventory(userId: String): SkillInventory {
        val toolBacked = toolsFilter
            .applyFilter(toolCatalog.toolsByCategory)
            .filterValues { it.isNotEmpty() }
            .mapValues { (_, tools) -> tools.keys.sorted() }
            .filterValues { it.isNotEmpty() }

        val fileBacked = try {
            skillRegistryRepository.listSkills(userId).sortedWith(
                compareBy<StoredSkill> { it.skillId.value }.thenBy { it.manifest.name }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn("Failed to load Skill inventory for user={}", userId, error)
            emptyList()
        }

        return SkillInventory(
            toolBackedByCategory = toolBacked,
            fileBacked = fileBacked,
        )
    }
}

private data class SkillInventory(
    val toolBackedByCategory: Map<ToolCategory, List<String>>,
    val fileBacked: List<StoredSkill>,
)

private class SkillInventoryPromptAugmenter {
    fun augment(
        systemPrompt: String,
        history: List<LLMRequest.Message>,
        inventory: SkillInventory,
    ): List<LLMRequest.Message> {
        val message = "$systemPrompt\n\n${inventoryBlock(inventory)}".toSystemPromptMessage()
        if (history.isEmpty()) return listOf(message)
        return if (history.first().role == LLMMessageRole.system) {
            listOf(message) + history.drop(1)
        } else {
            listOf(message) + history
        }
    }

    private fun inventoryBlock(inventory: SkillInventory): String = buildString {
        append("<skill_inventory>\n")
        append("Tool-backed Skills by category:\n")
        if (inventory.toolBackedByCategory.isEmpty()) {
            append("- none\n")
        } else {
            inventory.toolBackedByCategory.toSortedMap(compareBy { it.name }).forEach { (category, skillIds) ->
                append("- ")
                append(category.name)
                append(": ")
                append(skillIds.joinToString())
                append('\n')
            }
        }
        append("File-backed Skills:\n")
        if (inventory.fileBacked.isEmpty()) {
            append("- none\n")
        } else {
            inventory.fileBacked.forEach { skill ->
                append("- ")
                append(skill.skillId.value)
                append(": ")
                append(skill.manifest.name)
                val description = skill.manifest.description.trim().replace(Regex("\\s+"), " ")
                if (description.isNotBlank()) {
                    append(" - ")
                    append(description.take(MAX_DESCRIPTION_CHARS))
                }
                append('\n')
            }
        }
        append("</skill_inventory>")
    }

    private companion object {
        const val MAX_DESCRIPTION_CHARS = 180
    }
}
