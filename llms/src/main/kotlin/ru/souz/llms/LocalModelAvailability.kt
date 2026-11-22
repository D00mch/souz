package ru.souz.llms

interface LocalModelAvailability {
    fun availableGigaModels(): List<LLMModel>
    fun defaultGigaModel(): LLMModel?
    fun isProviderAvailable(): Boolean
}
