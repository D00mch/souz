package ru.souz.agent.skills

/** Reserved for host-loaded capabilities; stored bundles must never shadow this namespace. */
const val PLUGIN_SKILL_PREFIX = "plugin."

@JvmInline
value class SkillId(val value: String) {
    init {
        require(value.isNotBlank()) { "SkillId must not be blank." }
    }

    override fun toString(): String = value
}
