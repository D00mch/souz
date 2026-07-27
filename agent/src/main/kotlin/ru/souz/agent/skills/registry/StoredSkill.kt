package ru.souz.agent.skills.registry

import ru.souz.agent.skills.bundle.SkillManifest
import ru.souz.agent.skills.activation.SkillId
import java.time.Instant

/**
 * Lightweight persisted view of a registered skill.
 *
 * The [ru.souz.agent.skills.bundle.SkillBundle] is like a Movie, and the [StoredSkill] is a trailer.
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

/**
 * Metadata-only view for compact prompt inventory.
 *
 * This deliberately excludes bundle identity so filesystem implementations can list loose
 * Skill directories without reading supporting files or hashing the full bundle.
 */
data class SkillInventoryEntry(
    /** User that owns this registration namespace. */
    val userId: String,

    /** Stable canonical identifier used for on-demand lookup and execution. */
    val skillId: SkillId,

    /** Parsed `SKILL.md` frontmatter for non-prompt inventory consumers. */
    val manifest: SkillManifest,

    /** Creation or first-registration timestamp captured by the backing store. */
    val createdAt: Instant,
)

fun StoredSkill.toInventoryEntry(): SkillInventoryEntry = SkillInventoryEntry(
    userId = userId,
    skillId = skillId,
    manifest = manifest,
    createdAt = createdAt,
)
