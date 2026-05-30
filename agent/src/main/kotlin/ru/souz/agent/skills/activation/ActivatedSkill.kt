package ru.souz.agent.skills.activation

import ru.souz.agent.skills.bundle.SkillManifest

data class ActivatedSkill(
    val skillId: SkillId,
    val manifest: SkillManifest,
    val bundleHash: String,
    val instructionBody: String,
    val supportingFiles: List<String>,
)

@JvmInline
value class SkillId(val value: String) {
    init {
        require(value.isNotBlank()) { "SkillId must not be blank." }
    }

    override fun toString(): String = value
}