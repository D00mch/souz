package ru.souz.backend.config

import ru.souz.backend.common.BackendConfigurationException
import ru.souz.db.DEFAULT_REQUEST_TIMEOUT_MILLIS
import ru.souz.db.REGION_EN
import ru.souz.db.REGION_RU
import ru.souz.llms.DEFAULT_MAX_TOKENS
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmBuildProfileSettings
import ru.souz.llms.LlmProvider
import ru.souz.llms.ProviderSettings
import ru.souz.llms.findLLMModel

/** Immutable deployment settings loaded once with the backend application configuration. */
data class BackendSettingsConfig(
    val gigaChatKey: String? = null,
    val qwenChatKey: String? = null,
    val aiTunnelKey: String? = null,
    val anthropicKey: String? = null,
    val openaiKey: String? = null,
    override val openaiBaseUrl: String? = null,
    override val openaiModel: String? = null,
    val defaultCalendar: String? = null,
    override val regionProfile: String = REGION_RU,
    override val gigaModel: LLMModel = LLMModel.Max,
    val useFewShotExamples: Boolean = false,
    val useStreaming: Boolean = false,
    override val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val contextSize: Int = DEFAULT_MAX_TOKENS,
    val temperature: Float = 0.7f,
    override val embeddingsModel: EmbeddingsModel = EmbeddingsModel.GigaEmbeddings,
) : ProviderSettings, LlmBuildProfileSettings {
    init {
        require(gigaModel.provider != LlmProvider.LOCAL) {
            "The backend cannot use a local chat model."
        }
        require(embeddingsModel.provider != LlmProvider.LOCAL) {
            "The backend cannot use a local embeddings model."
        }
        require(requestTimeoutMillis > 0L) { "Backend request timeout must be positive." }
        require(contextSize > 0) { "Backend context size must be positive." }
        require(temperature.isFinite()) { "Backend temperature must be finite." }
    }

    fun hasKey(provider: LlmProvider): Boolean = when (provider) {
        LlmProvider.GIGA -> !gigaChatKey.isNullOrBlank()
        LlmProvider.QWEN -> !qwenChatKey.isNullOrBlank()
        LlmProvider.AI_TUNNEL -> !aiTunnelKey.isNullOrBlank()
        LlmProvider.ANTHROPIC -> !anthropicKey.isNullOrBlank()
        LlmProvider.OPENAI -> !openaiKey.isNullOrBlank()
        LlmProvider.CODEX,
        LlmProvider.LOCAL,
        -> false
    }

    companion object {
        fun load(source: BackendConfigSource = SystemBackendConfigSource): BackendSettingsConfig {
            rejectLocalFileConfiguration(source)
            val region = source.settingsText("APP_LANGUAGE", "APP_LANGUAGE")
                ?.lowercase()
                ?.takeIf { it == REGION_EN }
                ?: REGION_RU
            val defaultModel = source.settingsModel(
                envKey = "GIGA_MODEL",
                propertyKey = "GIGA_MODEL",
                default = LlmBuildProfile.defaultsForLanguage(region).values.first(),
            )
            val embeddingsModel = source.settingsEnum(
                envKey = "EMBEDDINGS_MODEL",
                propertyKey = "EMBEDDINGS_MODEL",
                default = EmbeddingsModel.GigaEmbeddings,
            ) { candidate, value ->
                candidate.name.equals(value, ignoreCase = true) ||
                    candidate.alias.equals(value, ignoreCase = true)
            }
            if (embeddingsModel.provider == LlmProvider.LOCAL) {
                throw BackendConfigurationException(
                    "Local embeddings model '${embeddingsModel.alias}' is unavailable in the backend."
                )
            }

            return BackendSettingsConfig(
                gigaChatKey = source.settingsText("GIGA_KEY", "GIGA_KEY"),
                qwenChatKey = source.settingsText("QWEN_KEY", "QWEN_KEY"),
                aiTunnelKey = source.settingsText("AITUNNEL_KEY", "AITUNNEL_KEY"),
                anthropicKey = source.settingsText("ANTHROPIC_API_KEY", "ANTHROPIC_API_KEY"),
                openaiKey = source.settingsText("OPENAI_API_KEY", "OPENAI_API_KEY"),
                openaiBaseUrl = source.settingsText("OPENAI_BASE_URL", "OPENAI_BASE_URL"),
                openaiModel = source.settingsText("OPENAI_MODEL", "OPENAI_MODEL"),
                defaultCalendar = source.settingsText("DEFAULT_CALENDAR", "DEFAULT_CALENDAR"),
                regionProfile = region,
                gigaModel = defaultModel,
                useFewShotExamples = source.settingsBoolean("USE_FEW_SHOTS", "USE_FEW_SHOTS", false),
                useStreaming = source.settingsBoolean(
                    envKey = "USE_STREAMING",
                    propertyKey = "USE_STREAMING",
                    default = source.settingsBoolean("USE_GRPC", "USE_GRPC", false),
                ),
                requestTimeoutMillis = source.settingsLong(
                    envKey = "REQUEST_TIMEOUT_MILLIS",
                    propertyKey = "REQUEST_TIMEOUT_MILLIS",
                    default = DEFAULT_REQUEST_TIMEOUT_MILLIS,
                ),
                contextSize = source.settingsInt(
                    envKey = "CONTEXT_SIZE",
                    propertyKey = "CONTEXT_SIZE",
                    default = DEFAULT_MAX_TOKENS,
                ),
                temperature = source.settingsFloat("TEMPERATURE", "TEMPERATURE", 0.7f),
                embeddingsModel = embeddingsModel,
            )
        }

        private fun rejectLocalFileConfiguration(source: BackendConfigSource) {
            val key = listOf("MCP_SERVERS_FILE", "MCP_SERVERS_JSON")
                .firstOrNull { candidate -> source.settingsText(candidate, candidate) != null }
                ?: return
            throw BackendConfigurationException(
                "$key is unavailable because backend MCP execution is disabled."
            )
        }
    }
}

private fun BackendConfigSource.settingsText(envKey: String, propertyKey: String): String? =
    value(envKey, propertyKey)?.trim()?.takeIf(String::isNotEmpty)

private fun BackendConfigSource.settingsBoolean(
    envKey: String,
    propertyKey: String,
    default: Boolean,
): Boolean {
    val rawValue = settingsText(envKey, propertyKey) ?: return default
    return rawValue.toBooleanStrictOrNull()
        ?: throw BackendConfigurationException("Invalid boolean value '$rawValue' for $envKey / $propertyKey.")
}

private fun BackendConfigSource.settingsInt(envKey: String, propertyKey: String, default: Int): Int {
    val rawValue = settingsText(envKey, propertyKey) ?: return default
    return rawValue.toIntOrNull()
        ?: throw BackendConfigurationException("Invalid integer value '$rawValue' for $envKey / $propertyKey.")
}

private fun BackendConfigSource.settingsLong(envKey: String, propertyKey: String, default: Long): Long {
    val rawValue = settingsText(envKey, propertyKey) ?: return default
    return rawValue.toLongOrNull()
        ?: throw BackendConfigurationException("Invalid long value '$rawValue' for $envKey / $propertyKey.")
}

private fun BackendConfigSource.settingsFloat(envKey: String, propertyKey: String, default: Float): Float {
    val rawValue = settingsText(envKey, propertyKey) ?: return default
    return rawValue.toFloatOrNull()
        ?: throw BackendConfigurationException("Invalid float value '$rawValue' for $envKey / $propertyKey.")
}

private fun BackendConfigSource.settingsModel(
    envKey: String,
    propertyKey: String,
    default: LLMModel,
): LLMModel {
    val rawValue = settingsText(envKey, propertyKey) ?: return default
    val model = findLLMModel(rawValue)
        ?: throw BackendConfigurationException("Unknown model '$rawValue' for $envKey / $propertyKey.")
    if (model.provider == LlmProvider.LOCAL) {
        throw BackendConfigurationException("Local model '$rawValue' is unavailable in the backend.")
    }
    return model
}

private inline fun <reified T : Enum<T>> BackendConfigSource.settingsEnum(
    envKey: String,
    propertyKey: String,
    default: T,
    matches: (T, String) -> Boolean,
): T {
    val rawValue = settingsText(envKey, propertyKey) ?: return default
    return enumValues<T>().firstOrNull { matches(it, rawValue) }
        ?: throw BackendConfigurationException("Unknown value '$rawValue' for $envKey / $propertyKey.")
}
