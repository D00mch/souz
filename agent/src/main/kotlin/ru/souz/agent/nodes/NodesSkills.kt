package ru.souz.agent.nodes

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Node
import ru.souz.agent.skills.activation.ActivatedSkill
import ru.souz.agent.skills.SkillActivationPipeline
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta

internal const val SKILLS_ACTIVATION_NODE_NAME = "Skills Activation"

internal class NodesSkills(
    private val pipeline: SkillActivationPipeline,
    private val skillCommandTool: LLMToolSetup? = null,
) {
    private val logger = LoggerFactory.getLogger(NodesSkills::class.java)

    fun node(name: String = SKILLS_ACTIVATION_NODE_NAME): Node<String, String> = Node(name) { ctx: AgentContext<String> ->
        val userId = ctx.toolInvocationMeta.userId.trim()

        try {
            when (val result = pipeline.run(SkillActivationPipeline.Input(userId = userId, context = ctx))) {
                is SkillActivationPipeline.Result.Ready -> {
                    logger.info(
                        "Skills activation completed for user={} selected={} activated={} rejected={}",
                        userId,
                        result.selectedSkillIds.size,
                        result.activatedSkills.size,
                        result.rejectedSkills.size,
                    )
                    if (result.rejectedSkills.isNotEmpty()) {
                        logger.warn(
                            "Skills activation rejected some skills for user={}: {}",
                            userId,
                            result.rejectedSkills.map { rejected ->
                                mapOf(
                                    "skillId" to rejected.skillId.value,
                                    "reason" to rejected.reason,
                                    "findings" to rejected.findings,
                                )
                            },
                        )
                    }
                    withSkillTools(result.context, result.activatedSkills)
                }

                is SkillActivationPipeline.Result.Blocked -> {
                    logger.warn(
                        "Skills activation blocked for user={} conversationId={} requestId={} reason={} selectedSkillIds={} findings={}",
                        userId,
                        ctx.toolInvocationMeta.conversationId,
                        ctx.toolInvocationMeta.requestId,
                        result.reason,
                        result.selectedSkillIds.map { it.value },
                        result.findings,
                    )
                    pipeline.withoutSkills(ctx)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logger.warn(
                "Skills activation failed open for user={} conversationId={} requestId={}",
                userId,
                ctx.toolInvocationMeta.conversationId,
                ctx.toolInvocationMeta.requestId,
                t,
            )
            pipeline.withoutSkills(ctx)
        }
    }

    private fun withSkillTools(
        context: AgentContext<String>,
        activatedSkills: List<ActivatedSkill>,
    ): AgentContext<String> {
        val commandTool = skillCommandTool ?: return context
        val managedToolNames = setOf(commandTool.fn.name)

        if (activatedSkills.isEmpty()) {
            return context.map(
                settings = context.settings.withDynamicSkillTools(
                    managedToolNames = managedToolNames,
                    skillTools = emptyList(),
                ),
                activeTools = context.activeTools.withDynamicSkillTools(
                    managedToolNames = managedToolNames,
                    skillTools = emptyList(),
                ),
            ) { it }
        }

        val skillTools = listOf(commandTool.withActivatedSkills(activatedSkills))
        val updatedSettings = context.settings.withDynamicSkillTools(
            managedToolNames = managedToolNames,
            skillTools = skillTools,
        )
        val updatedActiveTools = context.activeTools.withDynamicSkillTools(
            managedToolNames = managedToolNames,
            skillTools = skillTools,
        )

        return context.map(
            settings = updatedSettings,
            activeTools = updatedActiveTools,
        ) { it }
    }

    private fun LLMToolSetup.withActivatedSkills(activatedSkills: List<ActivatedSkill>): LLMToolSetup {
        val delegate = this
        val activeSkillArgs = activatedSkills.map { skill ->
            mapOf(
                "skillId" to skill.skillId.value,
                "bundleHash" to skill.bundleHash,
                "supportingFiles" to skill.supportingFiles,
            )
        }
        val description = buildSkillCommandDescription(delegate.fn.description, activatedSkills)
        return object : LLMToolSetup {
            override val fn: LLMRequest.Function = delegate.fn.copy(description = description)

            override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                delegate.invoke(functionCall.withActiveSkills(activeSkillArgs))

            override suspend fun invoke(
                functionCall: LLMResponse.FunctionCall,
                meta: ToolInvocationMeta,
            ): LLMRequest.Message =
                delegate.invoke(functionCall.withActiveSkills(activeSkillArgs), meta)
        }
    }

    private fun LLMResponse.FunctionCall.withActiveSkills(
        activeSkillArgs: List<Map<String, Any>>,
    ): LLMResponse.FunctionCall =
        copy(arguments = arguments + ("activeSkills" to activeSkillArgs))

    private fun buildSkillCommandDescription(
        baseDescription: String,
        activatedSkills: List<ActivatedSkill>,
    ): String = buildString {
        append(baseDescription.trim())
        append("\n\nActive Skills for this turn:\n")
        activatedSkills.forEach { skill ->
            append("- ")
            append(skill.skillId.value)
            if (skill.supportingFiles.isNotEmpty()) {
                append(" supporting files: ")
                append(skill.supportingFiles.joinToString(", "))
            }
            append('\n')
        }
        append("Do not call this tool merely to list or inspect the skill bundle files. ")
        append("Call it only when an active skill instruction requires executing a bundled script or command. ")
        append("For instruction-only/template-only skills, follow the injected skill instructions without calling this tool.")
    }

    private fun AgentSettings.withDynamicSkillTools(
        managedToolNames: Set<String>,
        skillTools: List<LLMToolSetup>,
    ): AgentSettings {
        if (managedToolNames.isEmpty()) {
            return this
        }

        val skillToolsByName = skillTools.associateBy { it.fn.name }
        val updatedByName = tools.byName
            .filterKeys { it !in managedToolNames }
            .plus(skillToolsByName)

        return copy(tools = tools.copy(byName = updatedByName))
    }

    private fun List<LLMRequest.Function>.withDynamicSkillTools(
        managedToolNames: Set<String>,
        skillTools: List<LLMToolSetup>,
    ): List<LLMRequest.Function> =
        filterNot { it.name in managedToolNames }
            .plus(skillTools.map { it.fn })
            .distinctBy { it.name }
}
