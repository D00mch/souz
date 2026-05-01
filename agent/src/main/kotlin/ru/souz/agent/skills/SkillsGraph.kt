package ru.souz.agent.skills

import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Graph
import ru.souz.agent.graph.Node
import ru.souz.agent.graph.buildGraph
import ru.souz.agent.skills.validation.SkillBundleHasher
import ru.souz.agent.skills.validation.SkillLlmValidationInput
import ru.souz.agent.skills.validation.SkillLlmValidator
import ru.souz.agent.skills.validation.SkillStaticValidator
import ru.souz.agent.skills.validation.SkillStructuralValidator
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationPolicy
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationReducer
import ru.souz.agent.skills.validation.SkillValidationResult
import ru.souz.agent.skills.validation.SkillValidationSeverity
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.agent.state.AgentContext
import java.time.Clock
import java.time.Instant

data class SkillsGraphInput(
    val userId: String,
    val context: AgentContext<String>,
    val policy: SkillValidationPolicy = SkillValidationPolicy.default(),
)

sealed interface SkillsGraphResult {
    data class Ready(
        val context: AgentContext<String>,
        val activatedSkills: List<ActivatedSkill>,
        val selectedSkillIds: List<SkillId>,
    ) : SkillsGraphResult

    data class Blocked(
        val reason: String,
        val findings: List<SkillValidationFinding>,
        val selectedSkillIds: List<SkillId>,
    ) : SkillsGraphResult
}

private typealias SkillCtx = AgentContext<SkillsGraphState>

class SkillsGraph(
    private val registryRepository: SkillRegistryRepository,
    private val selector: SkillSelector,
    private val llmValidator: SkillLlmValidator,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(SkillsGraph::class.java)

    private val skillProcessingGraph: Graph<SkillsGraphState, SkillsGraphState> = buildGraph(name = "SkillsGraph.Skill") {
        val loadBundle = Node<SkillsGraphState, SkillsGraphState>("Load skill metadata and files") { ctx ->
            val state = ctx.input
            val skillId = state.currentSkillIdOrNull()
                ?: return@Node ctx.map { state.internalError("Current skill is not prepared.") }
            val bundle = try {
                registryRepository.loadSkillBundle(state.request.userId, skillId)
                    ?: return@Node ctx.map {
                        state.blocked(
                            reason = "Skill bundle not found for ${skillId.value}",
                            code = "bundle.missing",
                        )
                    }
            } catch (t: Throwable) {
                logger.warn("Failed to load skill bundle for {}", skillId.value, t)
                return@Node ctx.map {
                    state.blocked(
                        reason = "Failed to load skill bundle for ${skillId.value}",
                        code = "bundle.load_error",
                    )
                }
            }

            ctx.map {
                state.copy(
                    currentSkill = state.requireCurrentSkill().copy(bundle = bundle),
                )
            }
        }

        val hashBundle = Node<SkillsGraphState, SkillsGraphState>("Compute canonical bundle hash") { ctx ->
            ctx.withLoadedSkill { state, currentSkill, bundle ->
                val bundleHash = try {
                    SkillBundleHasher.hash(bundle)
                } catch (t: Throwable) {
                    logger.warn("Failed to hash skill bundle for {}", currentSkill.skillId.value, t)
                    return@withLoadedSkill map {
                        state.blocked(
                            reason = "Failed to hash skill bundle for ${currentSkill.skillId.value}",
                            code = "bundle.hash_error",
                        )
                    }
                }

                registryRepository.invalidateOtherValidations(
                    userId = state.request.userId,
                    skillId = currentSkill.skillId,
                    activeBundleHash = bundleHash,
                    policyVersion = state.request.policy.policyVersion,
                    reason = "Bundle hash changed or newer bundle became active.",
                )

                map {
                    state.copy(
                        currentSkill = currentSkill.copy(bundleHash = bundleHash),
                    )
                }
            }
        }

        val checkValidationRepository = Node<SkillsGraphState, SkillsGraphState>("Check validation repository") { ctx ->
            ctx.withHashedSkill { state, currentSkill, _, bundleHash ->
                val cached = registryRepository.getValidation(
                    userId = state.request.userId,
                    skillId = currentSkill.skillId,
                    bundleHash = bundleHash,
                    policyVersion = state.request.policy.policyVersion,
                )

                when (cached?.status) {
                    SkillValidationStatus.APPROVED -> {
                        logger.info(
                            "Using cached skill validation for {} ({})",
                            currentSkill.skillId.value,
                            bundleHash.take(12),
                        )
                        map {
                            state.copy(
                                currentSkill = currentSkill.copy(cachedValidation = cached),
                            )
                        }
                    }

                    SkillValidationStatus.REJECTED -> {
                        map {
                            state.blocked(
                                reason = "Skill validation previously rejected for ${currentSkill.skillId.value}",
                                findings = cached.findings.ifEmpty {
                                    listOf(
                                        SkillValidationFinding(
                                            code = "validation.cached_reject",
                                            message = "Skill validation previously rejected for ${currentSkill.skillId.value}",
                                            severity = SkillValidationSeverity.ERROR,
                                        )
                                    )
                                },
                            )
                        }
                    }

                    SkillValidationStatus.STALE, null -> map {
                        state.copy(
                            currentSkill = currentSkill.copy(cachedValidation = cached),
                        )
                    }
                }
            }
        }

        val validateStructurally = Node<SkillsGraphState, SkillsGraphState>("Validate skill structure") { ctx ->
            ctx.withHashedSkill { state, currentSkill, bundle, bundleHash ->
                val structural = SkillStructuralValidator(state.request.policy).validate(bundle)
                if (structural.hasHardReject) {
                    val record = rejectionRecord(
                        state = state,
                        skillId = currentSkill.skillId,
                        bundleHash = bundleHash,
                        reasons = listOf("Structural validation failed."),
                        findings = structural.findings,
                    )
                    registryRepository.saveValidation(record)
                    return@withHashedSkill map {
                        state.blocked(
                            reason = "Skill validation blocked by structural validator for ${currentSkill.skillId.value}",
                            findings = structural.findings,
                        )
                    }
                }

                map {
                    state.copy(
                        currentSkill = currentSkill.copy(structuralValidation = structural),
                    )
                }
            }
        }

        val validateStatically = Node<SkillsGraphState, SkillsGraphState>("Validate skill files") { ctx ->
            ctx.withHashedSkill { state, currentSkill, bundle, bundleHash ->
                val static = SkillStaticValidator(state.request.policy).validate(bundle)
                if (static.hasHardReject) {
                    val record = rejectionRecord(
                        state = state,
                        skillId = currentSkill.skillId,
                        bundleHash = bundleHash,
                        reasons = listOf("Static validation failed."),
                        findings = static.findings,
                    )
                    registryRepository.saveValidation(record)
                    return@withHashedSkill map {
                        state.blocked(
                            reason = "Skill validation blocked by static validator for ${currentSkill.skillId.value}",
                            findings = static.findings,
                        )
                    }
                }

                map {
                    state.copy(
                        currentSkill = currentSkill.copy(staticValidation = static),
                    )
                }
            }
        }

        val validateWithLlm = Node<SkillsGraphState, SkillsGraphState>("Validate skill with LLM") { ctx ->
            ctx.withValidatedSkill { state, currentSkill, bundle, bundleHash, structural, static ->
                val llmVerdict = llmValidator.validate(
                    SkillLlmValidationInput(
                        userId = state.request.userId,
                        skillId = currentSkill.skillId,
                        bundleHash = bundleHash,
                        policy = state.request.policy,
                        manifest = bundle.manifest,
                        filePaths = bundle.files.map { it.normalizedPath },
                        skillMarkdown = bundle.skillMarkdownFile.contentAsText(),
                        supportingFileExcerpts = bundle.files
                            .filterNot { it.normalizedPath == SKILL_MD_PATH }
                            .associate { file ->
                                file.normalizedPath to file.contentAsText().take(state.request.policy.excerptCharsPerFile)
                            },
                        structuralFindings = structural.findings,
                        staticFindings = static.findings,
                    )
                )
                val record = SkillValidationReducer.reduce(
                    userId = state.request.userId,
                    skillId = currentSkill.skillId,
                    bundleHash = bundleHash,
                    policy = state.request.policy,
                    structural = structural,
                    static = static,
                    llm = llmVerdict,
                    createdAt = Instant.now(clock),
                )
                registryRepository.saveValidation(record)
                if (record.status != SkillValidationStatus.APPROVED) {
                    return@withValidatedSkill map {
                        state.blocked(
                            reason = "Skill validation rejected for ${currentSkill.skillId.value}",
                            findings = record.findings.ifEmpty {
                                listOf(
                                    SkillValidationFinding(
                                        code = "validation.rejected",
                                        message = "Skill validation rejected for ${currentSkill.skillId.value}",
                                        severity = SkillValidationSeverity.ERROR,
                                    )
                                )
                            },
                        )
                    }
                }

                this
            }
        }

        val activateSkill = Node<SkillsGraphState, SkillsGraphState>("Activate skill") { ctx ->
            ctx.withHashedSkill { state, currentSkill, bundle, bundleHash ->
                map {
                    state.copy(
                        nextSkillIndex = state.nextSkillIndex + 1,
                        activatedSkills = state.activatedSkills + bundle.toActivatedSkill(bundleHash),
                        currentSkill = null,
                    )
                }
            }
        }

        nodeInput.edgeTo(loadBundle)
        loadBundle.edgeTo(hashBundle)
        hashBundle.edgeTo(checkValidationRepository)
        checkValidationRepository.edgeTo { graphCtx ->
            val state = graphCtx.input
            when {
                state.result != null -> nodeFinish
                state.requireCurrentSkill().cachedValidation?.status == SkillValidationStatus.APPROVED -> activateSkill
                else -> validateStructurally
            }
        }
        validateStructurally.edgeTo { graphCtx ->
            if (graphCtx.input.result != null) nodeFinish else validateStatically
        }
        validateStatically.edgeTo { graphCtx ->
            if (graphCtx.input.result != null) nodeFinish else validateWithLlm
        }
        validateWithLlm.edgeTo { graphCtx ->
            if (graphCtx.input.result != null) nodeFinish else activateSkill
        }
        activateSkill.edgeTo(nodeFinish)
    }

    private val detectRequestedSkills = Node<SkillsGraphState, SkillsGraphState>("Detect requested or triggered skills") { ctx ->
        val state = ctx.input
        val availableSkills = registryRepository.listSkills(state.request.userId)
        val selection = selector.select(
            SkillSelectionInput(
                userMessage = state.request.context.input,
                availableSkills = availableSkills,
            )
        )
        val selectedIds = selection.selectedSkillIds.distinct()
        if (selectedIds.isEmpty()) {
            return@Node ctx.map {
                state.copy(
                    selectedSkillIds = emptyList(),
                    result = SkillsGraphResult.Ready(
                        context = SkillsContextInjector.inject(state.request.context, activatedSkills = emptyList()),
                        activatedSkills = emptyList(),
                        selectedSkillIds = emptyList(),
                    ),
                )
            }
        }

        val availableById = availableSkills.associateBy { it.skillId }
        val unknownSkill = selectedIds.firstOrNull { it !in availableById }
        if (unknownSkill != null) {
            return@Node ctx.map {
                state.copy(
                    selectedSkillIds = selectedIds,
                    result = blocked(
                        reason = "Skill selector returned an unknown skill id: ${unknownSkill.value}",
                        code = "selector.unknown_skill",
                        selectedSkillIds = selectedIds,
                    ),
                )
            }
        }

        ctx.map {
            state.copy(
                selectedSkillIds = selectedIds,
            )
        }
    }

    private val prepareNextSkill = Node<SkillsGraphState, SkillsGraphState>("Prepare next skill") { ctx ->
        val state = ctx.input
        val skillId = state.selectedSkillIds.getOrNull(state.nextSkillIndex)
            ?: return@Node ctx.map { state.internalError("No skill available at index ${state.nextSkillIndex}.") }
        ctx.map {
            state.copy(
                currentSkill = CurrentSkillState(skillId = skillId),
            )
        }
    }

    private val finalizeReady = Node<SkillsGraphState, SkillsGraphState>("Finalize activated skills") { ctx ->
        val state = ctx.input
        val updatedContext = SkillsContextInjector.inject(state.request.context, state.activatedSkills)
        ctx.map {
            state.copy(
                result = SkillsGraphResult.Ready(
                    context = updatedContext,
                    activatedSkills = state.activatedSkills,
                    selectedSkillIds = state.selectedSkillIds,
                ),
            )
        }
    }

    private val resultNode = Node<SkillsGraphState, SkillsGraphResult>("Return graph result") { ctx ->
        ctx.map { state ->
            state.result ?: state.internalError("Skills graph finished without a result.").result!!
        }
    }

    private val graph: Graph<SkillsGraphState, SkillsGraphResult> = buildGraph(name = "SkillsGraph") {
        nodeInput.edgeTo(detectRequestedSkills)
        detectRequestedSkills.edgeTo { graphCtx ->
            if (graphCtx.input.result != null) resultNode else prepareNextSkill
        }
        prepareNextSkill.edgeTo(skillProcessingGraph)
        skillProcessingGraph.edgeTo { graphCtx ->
            val state = graphCtx.input
            when {
                state.result != null -> resultNode
                state.nextSkillIndex < state.selectedSkillIds.size -> prepareNextSkill
                else -> finalizeReady
            }
        }
        finalizeReady.edgeTo(resultNode)
        resultNode.edgeTo(nodeFinish)
    }

    suspend fun run(input: SkillsGraphInput): SkillsGraphResult {
        return graph.start(input.context.map { SkillsGraphState(request = input) })
            .input
    }

    private fun rejectionRecord(
        state: SkillsGraphState,
        skillId: SkillId,
        bundleHash: String,
        reasons: List<String>,
        findings: List<SkillValidationFinding>,
    ): SkillValidationRecord = SkillValidationRecord(
        userId = state.request.userId,
        skillId = skillId,
        bundleHash = bundleHash,
        status = SkillValidationStatus.REJECTED,
        policyVersion = state.request.policy.policyVersion,
        validatorVersion = state.request.policy.validatorVersion,
        reasons = reasons,
        findings = findings,
        createdAt = Instant.now(clock),
    )

    private fun SkillsGraphState.blocked(
        reason: String,
        code: String,
    ): SkillsGraphState = copy(
        result = blocked(reason, code, selectedSkillIds),
    )

    private fun SkillsGraphState.blocked(
        reason: String,
        findings: List<SkillValidationFinding>,
    ): SkillsGraphState = copy(
        result = SkillsGraphResult.Blocked(
            reason = reason,
            findings = findings,
            selectedSkillIds = selectedSkillIds,
        ),
    )

    private fun SkillsGraphState.internalError(message: String): SkillsGraphState =
        blocked(
            reason = message,
            findings = listOf(
                SkillValidationFinding(
                    code = "skills.internal_error",
                    message = message,
                    severity = SkillValidationSeverity.ERROR,
                )
            ),
        )

    private fun blocked(
        reason: String,
        code: String,
        selectedSkillIds: List<SkillId>,
    ) = SkillsGraphResult.Blocked(
        reason = reason,
        findings = listOf(
            SkillValidationFinding(
                code = code,
                message = reason,
                severity = SkillValidationSeverity.ERROR,
            )
        ),
        selectedSkillIds = selectedSkillIds,
    )

    private fun SkillBundle.toActivatedSkill(bundleHash: String): ActivatedSkill = ActivatedSkill(
        skillId = skillId,
        manifest = manifest,
        bundleHash = bundleHash,
        instructionBody = skillMarkdownBody,
        supportingFiles = files.map { it.normalizedPath }.filterNot { it == SKILL_MD_PATH },
    )

    private inline fun SkillCtx.withLoadedSkill(
        block: SkillCtx.(SkillsGraphState, CurrentSkillState, SkillBundle) -> SkillCtx,
    ): SkillCtx {
        val state = input
        val currentSkill = state.requireCurrentSkill()
        val bundle = currentSkill.bundle
            ?: return map { state.internalError("Current skill bundle is missing.") }
        return block(state, currentSkill, bundle)
    }

    private inline fun SkillCtx.withHashedSkill(
        block: SkillCtx.(SkillsGraphState, CurrentSkillState, SkillBundle, String) -> SkillCtx,
    ): SkillCtx = withLoadedSkill { state, currentSkill, bundle ->
        val bundleHash = currentSkill.bundleHash
            ?: return@withLoadedSkill map { state.internalError("Current skill bundle hash is missing.") }
        block(state, currentSkill, bundle, bundleHash)
    }

    private inline fun SkillCtx.withValidatedSkill(
        block: SkillCtx.(SkillsGraphState, CurrentSkillState, SkillBundle, String, SkillValidationResult, SkillValidationResult) -> SkillCtx,
    ): SkillCtx = withHashedSkill { state, currentSkill, bundle, bundleHash ->
        val structural = currentSkill.structuralValidation
            ?: return@withHashedSkill map { state.internalError("Structural validation result is missing.") }
        val static = currentSkill.staticValidation
            ?: return@withHashedSkill map { state.internalError("Static validation result is missing.") }
        block(state, currentSkill, bundle, bundleHash, structural, static)
    }
}

private data class SkillsGraphState(
    val request: SkillsGraphInput,
    val selectedSkillIds: List<SkillId> = emptyList(),
    val nextSkillIndex: Int = 0,
    val activatedSkills: List<ActivatedSkill> = emptyList(),
    val currentSkill: CurrentSkillState? = null,
    val result: SkillsGraphResult? = null,
) {
    fun requireCurrentSkill(): CurrentSkillState = requireNotNull(currentSkill) {
        "Current skill is not prepared."
    }

    fun currentSkillIdOrNull(): SkillId? = currentSkill?.skillId
}

private data class CurrentSkillState(
    val skillId: SkillId,
    val bundle: SkillBundle? = null,
    val bundleHash: String? = null,
    val cachedValidation: SkillValidationRecord? = null,
    val structuralValidation: SkillValidationResult? = null,
    val staticValidation: SkillValidationResult? = null,
)
