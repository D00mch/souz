package ru.souz.agent.skills

/**
 * Explicit execution phases for the skills pipeline.
 *
 * The state machine advances through these phases linearly for each selected skill:
 *
 * After all selected skills are processed, the pipeline moves to INJECT_CONTEXT and
 * then DONE.
 *
 * Any exception or rejection in a phase should be converted into
 * [SkillActivationPipeline.Result.Blocked], so public callers only observe either Ready or Blocked.
 */
internal enum class SkillActivationPhase {
    /** Ask the selector abstraction which skills are relevant for the current request. */
    SELECT_SKILLS,

    /** Load the currently selected skill bundle from its registered location. */
    LOAD_BUNDLE,

    /**
     * The hash is used as part of the validation cache key, so any bundle content
     * change must result in a different hash and force revalidation.
     */
    HASH_BUNDLE,

    /**
     * Check cached validation state for the current skill, bundle hash, and policy.
     *
     * Approved cached validations may skip validation and proceed directly to
     * activation. Rejected cached validations should block. Missing or stale cache
     * entries continue to validation.
     */
    CHECK_CACHE,

    /**
     * Run deterministic structural validation on the loaded bundle.
     *
     * This should cover bundle shape, required files, size limits, and other
     * non-LLM checks that can be enforced locally.
     */
    STRUCTURAL_VALIDATE,

    /**
     * Run deterministic static policy validation.
     *
     * This should catch known unsafe patterns before any LLM-based validation is
     * attempted. Static validation remains authoritative and must not be bypassed
     * by LLM approval.
     */
    STATIC_VALIDATE,

    /** Run LLM-assisted validation for checks that require semantic judgment. */
    LLM_VALIDATE,

    /** Mark the current skill as activated after successful validation or cache approval. */
    ACTIVATE_SKILL,

    /** Advance to the next selected skill, or move to context injection if all is processed */
    NEXT_SKILL,

    /** Inject activated skills into AgentContext.history. */
    INJECT_CONTEXT,

    /** Once reached, the state must contain a final [Result.Ready] or [Result.Blocked]. */
    DONE,
}

internal val SkillActivationPhase.failureCode: String
    get() = when (this) {
        SkillActivationPhase.SELECT_SKILLS -> "skill.selector_failed"
        SkillActivationPhase.LOAD_BUNDLE -> "skill.bundle_load_failed"
        SkillActivationPhase.HASH_BUNDLE -> "skill.bundle_hash_failed"
        SkillActivationPhase.CHECK_CACHE -> "skill.validation_cache_failed"
        SkillActivationPhase.STRUCTURAL_VALIDATE -> "skill.structural_validation_failed"
        SkillActivationPhase.STATIC_VALIDATE -> "skill.static_validation_failed"
        SkillActivationPhase.LLM_VALIDATE -> "skill.llm_validation_failed"
        SkillActivationPhase.INJECT_CONTEXT -> "skill.context_injection_failed"
        SkillActivationPhase.ACTIVATE_SKILL -> "skill.activate_failed"
        SkillActivationPhase.NEXT_SKILL -> "skill.next_failed"
        SkillActivationPhase.DONE -> "skill.done_failed"
    }
