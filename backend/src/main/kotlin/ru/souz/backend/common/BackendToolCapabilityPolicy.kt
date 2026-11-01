package ru.souz.backend.common

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.skills.PLUGIN_SKILL_PREFIX
import ru.souz.backend.personality.PersonalityTool
import ru.souz.tool.ToolCategory
import ru.souz.tool.files.ToolGenerateImage
import ru.souz.tool.files.ToolViewImage

/**
 * The backend's only answer to which tools it may host, advertise, and execute.
 *
 * Bootstrap capabilities, `enabledTools` validation, and request-scoped execution selection all
 * read this object, so adding or moving a backend tool is one category or name change here.
 */
object BackendToolCapabilityPolicy {
    /** Process/runtime categories the backend can host. Unlisted categories stay desktop-only. */
    val safeCategories: Set<ToolCategory> = setOf(
        ToolCategory.FILES,
        ToolCategory.IMAGE,
        ToolCategory.IMAGE_GENERATION,
        ToolCategory.DATA_ANALYTICS,
        ToolCategory.CALCULATOR,
        ToolCategory.CHANNEL_MESSAGING,
        ToolCategory.OAUTH,
        ToolCategory.SCHEDULING,
    )

    /** Advertised tools whose LLM dependency is bound per execution, so no process catalog holds them. */
    val executionBoundToolNames: Set<String> = setOf(ToolViewImage.NAME, ToolGenerateImage.NAME, PersonalityTool.NAME)

    /** Names the backend advertises and accepts in a user's `enabledTools`, in stable order. */
    fun advertisedToolNames(
        processToolCatalog: AgentToolCatalog,
        pluginToolCatalog: AgentToolCatalog = AgentToolCatalog(),
    ): Set<String> =
        (hostableTools(processToolCatalog).toolsByCategory.values.flatMap { it.keys } +
            executionBoundToolNames + pluginTools(pluginToolCatalog).toolsByCategory.values.flatMap { it.keys }).toSortedSet()

    /**
     * Compiled tools one execution may call: the hostable process tools plus the execution-bound
     * LLM tools, narrowed to [enabledToolNames]. Web search belongs to the client Skill catalog.
     */
    fun selectExecutionTools(
        processToolCatalog: AgentToolCatalog,
        executionLlmToolCatalog: AgentToolCatalog,
        enabledToolNames: Set<String>?,
        pluginToolCatalog: AgentToolCatalog = AgentToolCatalog(),
    ): AgentToolCatalog {
        val isEnabled = { toolName: String -> enabledToolNames == null || toolName in enabledToolNames }
        return AgentToolCatalog.compose(
            hostableTools(processToolCatalog, isEnabled),
            hostableTools(executionLlmToolCatalog) { it in executionBoundToolNames && isEnabled(it) },
            pluginTools(pluginToolCatalog).filter { _, tool -> isEnabled(tool.fn.name) },
        )
    }

    /** The hostable view of a compiled catalog: safe categories and the named personality tool. */
    private fun hostableTools(catalog: AgentToolCatalog, allow: (String) -> Boolean = { true }): AgentToolCatalog =
        catalog.filter { category, tool ->
            !tool.fn.name.startsWith(PLUGIN_SKILL_PREFIX) &&
                (category in safeCategories || tool.fn.name == PersonalityTool.NAME) && allow(tool.fn.name)
        }

    /** Only the host's applied plugin catalog contributes reserved capabilities, regardless of category. */
    private fun pluginTools(catalog: AgentToolCatalog): AgentToolCatalog =
        catalog.filter { _, tool -> isPluginToolName(tool.fn.name) }

    fun isPluginToolName(name: String): Boolean = name.length <= 64 && pluginToolName.matches(name)

    private val pluginToolName = Regex(Regex.escape(PLUGIN_SKILL_PREFIX) + "[a-z][a-z0-9_]{0,39}\\.[a-z][a-z0-9_]{0,39}")
}
