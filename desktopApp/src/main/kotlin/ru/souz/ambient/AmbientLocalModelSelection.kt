package ru.souz.ambient

import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

fun selectAmbientLocalModel(
    configuredModel: LLMModel,
    isModelAvailable: (LLMModel) -> Boolean,
    defaultLocalModel: () -> LLMModel?,
): LLMModel {
    if (configuredModel.provider == LlmProvider.LOCAL && isModelAvailable(configuredModel)) {
        return configuredModel
    }
    return defaultLocalModel() ?: LLMModel.LocalQwen3_4B_Instruct_2507
}
