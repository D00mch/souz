package ru.souz.service.speech

import ru.souz.db.REGION_EN

enum class SpeechRecognitionLanguage(
    val apiCode: String,
    val localeTag: String,
) {
    RU(apiCode = "ru", localeTag = "ru-RU"),
    EN(apiCode = "en", localeTag = "en-US"),
    ;

    companion object {
        fun fromLanguageCode(language: String): SpeechRecognitionLanguage =
            if (language.equals(REGION_EN, ignoreCase = true)) EN else RU
    }
}

fun interface SpeechRecognitionLanguageProvider {
    fun current(): SpeechRecognitionLanguage
}
