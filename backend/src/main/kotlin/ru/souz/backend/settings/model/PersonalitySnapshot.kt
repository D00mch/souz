package ru.souz.backend.settings.model

data class PersonalitySnapshot(val prompt: String? = null, val revision: Long = 1) {
    companion object {
        const val MAX_PROMPT_LENGTH = 16_384
        const val MAX_REVISION = 9_007_199_254_740_991L
    }
}
