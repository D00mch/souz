package ru.souz.llms

interface LlmBuildProfileSettings {
    val regionProfile: String
}

interface LocalModelAvailability {
    fun availableGigaModels(): List<LLMModel>
    fun defaultGigaModel(): LLMModel?
    fun isProviderAvailable(): Boolean
}
