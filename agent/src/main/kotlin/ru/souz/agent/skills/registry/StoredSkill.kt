package ru.souz.agent.skills.registry

import ru.souz.agent.skills.bundle.SkillManifest
import ru.souz.agent.skills.activation.SkillId
import java.time.Instant

/**
 * Lightweight persisted view of a registered skill.
 *
 * This record is intentionally cheaper than [ru.souz.agent.skills.bundle.SkillBundle]: it
 * exposes the manifest, stable id, and current bundle hash used by selection and cache lookup
 * without forcing callers to load all bundle files.
 */
data class StoredSkill(
    /** User that owns this registration namespace. */
    val userId: String,

    /** Stable canonical identifier used across selection, validation, and activation. */
    val skillId: SkillId,

    /** Parsed `SKILL.md` frontmatter surfaced to selectors and management UIs. */
    val manifest: SkillManifest,

    /** Content hash of the currently stored bundle, used by validation cache keys. */
    val bundleHash: String,

    /** Creation or first-registration timestamp captured by the backing store. */
    val createdAt: Instant,
)
