package ru.souz.agent.skills

@JvmInline
value class SkillId(val value: String) {
    init {
        require(value.isNotBlank()) { "SkillId must not be blank." }
    }

    override fun toString(): String = value
}
