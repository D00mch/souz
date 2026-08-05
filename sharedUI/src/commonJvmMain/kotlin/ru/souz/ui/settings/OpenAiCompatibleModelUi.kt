package ru.souz.ui.settings

import ru.souz.llms.LLMModel

fun LLMModel.displayNameForConfiguredOpenAiModel(openaiModel: String): String =
    if (this == LLMModel.OpenAICompatibleCustom) {
        openaiModel.trim()
            .takeIf { it.isNotBlank() }
            ?.let { "OpenAI-compatible: $it" }
            ?: displayName
    } else {
        displayName
    }
