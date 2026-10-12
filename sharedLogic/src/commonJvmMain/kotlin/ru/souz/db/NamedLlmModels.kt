package ru.souz.db

/** Resolved server configuration. Secrets are deliberately excluded from toString(). */
class OpenAiConnection(val baseUrl: String, val apiKey: String)

data class NamedLlmModels(
    val connections: Map<String, OpenAiConnection> = emptyMap(),
    val models: Map<String, NamedLlmModel> = emptyMap(),
    val subagentModels: Map<String, NamedLlmModel> = emptyMap(),
    val summarization: NamedLlmModel? = null,
    val summarizationContextSize: Int? = null,
) {
    companion object {
        val EMPTY = NamedLlmModels()
    }
}

data class NamedLlmModel(val modelId: String, val connectionId: String)
