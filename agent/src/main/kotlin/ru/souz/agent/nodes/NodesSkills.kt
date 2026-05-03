package ru.souz.agent.nodes

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Node
import ru.souz.agent.skills.SkillActivationPipeline
import ru.souz.agent.state.AgentContext

internal const val SKILLS_ACTIVATION_NODE_NAME = "Skills Activation"

internal class NodesSkills(
    private val pipeline: SkillActivationPipeline,
) {
    private val logger = LoggerFactory.getLogger(NodesSkills::class.java)

    fun node(name: String = SKILLS_ACTIVATION_NODE_NAME): Node<String, String> = Node(name) { ctx: AgentContext<String> ->
        val userId = ctx.toolInvocationMeta.userId?.trim()
        if (userId.isNullOrEmpty()) {
            logger.warn(
                "Skipping skills activation because toolInvocationMeta.userId is missing. conversationId={}, requestId={}",
                ctx.toolInvocationMeta.conversationId,
                ctx.toolInvocationMeta.requestId,
            )
            return@Node ctx
        }

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
                    result.context
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
                    ctx
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
            ctx
        }
    }
}
