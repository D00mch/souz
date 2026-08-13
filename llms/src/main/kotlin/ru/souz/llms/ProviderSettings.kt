package ru.souz.llms

/** Read-only request settings shared by provider transports and routing. */
interface ProviderSettings {
    val gigaModel: LLMModel
    val embeddingsModel: EmbeddingsModel
    val openaiBaseUrl: String?
    val openaiModel: String?
    val requestTimeoutMillis: Long
}
