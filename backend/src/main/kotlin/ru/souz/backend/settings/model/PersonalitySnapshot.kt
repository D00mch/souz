package ru.souz.backend.settings.model

data class PersonalitySnapshot(val parameters: PersonalityParameters = PersonalityParameters(), val revision: Long = 1) {
    companion object {
        const val MAX_PROMPT_LENGTH = 16_384
        const val MAX_REVISION = 9_007_199_254_740_991L
    }
}

data class PersonalityParameters(
    val voice: String = "joy",
    val appeal: String = "no_official",
    val style: String = "informal",
    val personality: String = "",
) {
    init {
        require(voice in setOf("joy", "afina", "sber")) { "Unsupported voice." }
        require(appeal in setOf("official", "no_official")) { "Unsupported appeal." }
        require(style in setOf("informal", "neutral", "formal")) { "Unsupported style." }
        require(personality.length <= PersonalitySnapshot.MAX_PROMPT_LENGTH && '\u0000' !in personality) { "Invalid personality text." }
    }

    fun forChannel(yourName: String?): Map<String, String> = buildMap {
        put("voice", voice)
        put("appeal", appeal)
        put("style", style)
        put("personality", personality)
        if (yourName != null) put("your_name", yourName)
    }

    companion object {
        private val defaults = PersonalityParameters()

        fun from(values: Map<String, String>) = PersonalityParameters(
            voice = values["voice"] ?: defaults.voice,
            appeal = values["appeal"] ?: defaults.appeal,
            style = values["style"] ?: defaults.style,
            personality = values["personality"].orEmpty(),
        )
    }
}
