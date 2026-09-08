package ru.souz.tool.subagent

import ru.souz.agent.Agent
import ru.souz.agent.SubagentTool
import ru.souz.agent.SubagentInputException
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ModelResolution
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.resolveChatModel
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import ru.souz.tool.immutableToolCatalogSnapshot
import ru.souz.tool.skills.SkillCommandExecutor
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsByCategory
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import ru.souz.tool.skills.ToolInvokeSkill
import ru.souz.tool.skills.fileSkillExecutionSchema
import ru.souz.tool.skills.toDetail

/** Creates the core spawn tool with the parent's actual execution settings. */
class SubagentToolFactory(
    private val createAgent: (maxTurns: Int) -> Agent,
    private val settingsProvider: AgentSettingsProvider,
    private val toolCatalog: AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
    private val skillBundleProvider: SkillBundleProvider,
    private val commandExecutor: SkillCommandExecutor,
    private val approvalGate: SkillApprovalGate? = null,
    private val availableModels: () -> List<LLMModel> = { LLMModel.entries },
) {
    fun create(parentSettings: AgentSettings): LLMToolSetup =
        SubagentTool(createAgent) { input, meta ->
            prepare(input, parentSettings, meta)
        }

    internal suspend fun prepare(
        input: SubagentTool.Input,
        parentSettings: AgentSettings,
        meta: ToolInvocationMeta,
    ): SubagentTool.Setup {
        val model = input.model?.let { resolveModel(it, parentSettings) } ?: parentSettings.model
        val catalog = immutableToolCatalogSnapshot(toolCatalog.toolsByCategory)
        val enabled = AgentTools(toolsFilter.applyFilter(catalog.toolsByCategory))
        val tools = mutableListOf<LLMToolSetup>()
        val bundles = linkedMapOf<SkillId, SkillBundle>()
        input.skillIds.map(String::trim).distinct().forEach { id ->
            if (id.isBlank()) fail("invalid_skill_id", "Skill ID must not be blank.")
            if (id in RESTRICTED_TOOLS) fail("skill_not_allowed", "This core tool cannot be delegated: $id")
            val compiled = enabled.byName[id]
            if (compiled != null) {
                // Check the function too: a malformed catalog must not alias a restricted helper.
                if (compiled.fn.name in RESTRICTED_TOOLS) {
                    fail("skill_not_allowed", "This core tool cannot be delegated: $id")
                }
                tools += compiled
            } else {
                val skillId = SkillId(id)
                val bundle = skillBundleProvider.loadSkillBundle(meta.userId, skillId)
                    ?: if (catalog.toolsByCategory.values.any { id in it }) {
                        fail("skill_disabled", "Tool-backed Skill is disabled: $id")
                    } else {
                        fail("skill_not_found", "Skill is unavailable: $id")
                    }
                bundles[skillId] = when (val approval = approvalGate?.ensureApproved(
                    SkillApprovalGate.Input(meta.userId, skillId, bundle)
                )) {
                    is SkillApprovalGate.Result.Approved -> approval.bundle
                    is SkillApprovalGate.Result.Rejected -> fail("skill_validation_rejected", approval.reason)
                    null -> bundle
                }
            }
        }
        if (bundles.isNotEmpty()) tools += bundleCommandTool(bundles.toMap(), meta.userId)
        return SubagentTool.Setup(
            settings = parentSettings.copy(model = model, tools = enabled),
            tools = tools.toList(),
            systemPrompt = systemPrompt(bundles.values),
        )
    }

    private fun resolveModel(requested: String, parentSettings: AgentSettings): String {
        val parent = resolvedModel(parentSettings.model, settingsProvider.gigaModel)
        val model = resolvedModel(requested, parent)
        if (model.provider != parent.provider || model !in availableModels()) {
            fail("subagent_model_unavailable", "Choose an available model within the parent's ${parent.provider} provider.")
        }
        return model.alias
    }

    private fun resolvedModel(raw: String, preferred: LLMModel): LLMModel =
        when (val resolution = resolveChatModel(raw, preferredModel = preferred)) {
            is ModelResolution.Resolved -> resolution.value
            else -> fail("subagent_model_unavailable", "Unknown or ambiguous model: $raw")
        }

    private fun bundleCommandTool(bundles: Map<SkillId, SkillBundle>, ownerId: String): LLMToolSetup {
        val command = ToolInvokeSkill(
            toolCatalog = immutableToolCatalogSnapshot(emptyMap()),
            toolsFilter = object : AgentToolsFilter {
                override fun applyFilter(toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>) =
                    toolsByCategory
            },
            loadBundle = { userId, skillId ->
                bundles[skillId].takeIf { userId == ownerId }
            },
            commandExecutor = commandExecutor,
            approvalGate = null, // Selected bundles passed the host's approval policy before spawning.
        )
        return object : LLMToolSetup by command {
            override val fn = command.fn.copy(
                description = "Run a command for a selected file-backed Skill using its instructions and execution schema in the system prompt.",
                parameters = command.fn.parameters.copy(properties = command.fn.parameters.properties + (
                    "skillId" to LLMRequest.Property("string", "Selected Skill ID.", enum = bundles.keys.map { it.value })
                )),
            )
        }
    }

    private fun systemPrompt(bundles: Collection<SkillBundle>): String = buildString {
        append("Complete the delegated task using only the supplied context and selected tools. ")
        append("Return a final answer to the parent agent. You cannot delegate to another agent.")
        if (bundles.isNotEmpty()) {
            append("\nSelected file-backed Skills follow. Invoke their commands with RunSkillCommand, ")
            append("using skillId and arguments matching this shared execution schema:\n")
            append(restJsonMapper.writeValueAsString(fileSkillExecutionSchema()))
            bundles.forEach { bundle ->
                append("\n\n")
                append(restJsonMapper.writeValueAsString(bundle.toDetail()))
            }
        }
    }

    private fun fail(code: String, message: String): Nothing = throw SubagentInputException(code, message)

    private companion object {
        val RESTRICTED_TOOLS = setOf(
            SubagentTool.NAME,
            ToolInvokeSkill.NAME,
            ToolGetSkillByName.NAME,
            ToolGetSkillsByCategory.NAME,
            ToolGetSkillsNamesByCategory.NAME,
        )
    }
}
