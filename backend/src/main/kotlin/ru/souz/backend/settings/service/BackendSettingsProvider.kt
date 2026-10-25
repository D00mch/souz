package ru.souz.backend.settings.service

import com.fasterxml.jackson.module.kotlin.readValue
import ru.souz.agent.AgentId
import ru.souz.backend.config.NamedLlmConfig
import ru.souz.backend.config.loadNamedModels
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.config.SystemBackendConfigSource
import ru.souz.backend.common.BackendLlmSupport
import ru.souz.backend.settings.repository.BackendServerPreferenceStore
import ru.souz.db.DEFAULT_REQUEST_TIMEOUT_MILLIS
import ru.souz.db.REGION_EN
import ru.souz.db.REGION_RU
import ru.souz.db.SettingsProvider
import ru.souz.db.SUBAGENT_MODELS_JSON
import ru.souz.db.parseSubagentModels
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.findLLMModel
import ru.souz.llms.restJsonMapper

class BackendSettingsProvider(
    private val preferenceStore: BackendServerPreferenceStore,
    private val source: BackendConfigSource = SystemBackendConfigSource,
    namedModels: NamedLlmConfig = source.loadNamedModels(),
) : SettingsProvider {
    override val subagentModels: Map<String, LlmProvider> = parseSubagentModels(
        source.value(SUBAGENT_MODELS_JSON, SUBAGENT_MODELS_JSON),
        BackendLlmSupport.chatProviders,
    ).also { legacy ->
        require(legacy.keys.intersect(namedModels.models.keys).isEmpty()) { "SUBAGENT_MODELS_JSON IDs must not overlap named model selections." }
    } + namedModels.subagentModels.associateWith { LlmProvider.OPENAI }

    private val promptOverrides = mutableMapOf<Pair<AgentId, LLMModel>, String>()
    private val llmBuildProfile by lazy { LlmBuildProfile(this) }

    override var gigaChatKey: String? = configured("GIGA_KEY")
    override var qwenChatKey: String? = configured("QWEN_KEY")
    override var aiTunnelKey: String? = configured("AITUNNEL_KEY")
    override var anthropicKey: String? = configured("ANTHROPIC_API_KEY")
    override var openaiKey: String? = configured("OPENAI_API_KEY")
    override var openaiBaseUrl: String? = configured(OPENAI_BASE_URL)
    override var openaiModel: String? = configured(OPENAI_MODEL)
    override val openaiSummarizationApiKey: String? = namedModels.summarization?.apiKey ?: configured("OPENAI_SUMMARIZATION_API_KEY")
    override val openaiSummarizationBaseUrl: String? = namedModels.summarization?.baseUrl ?: configured("OPENAI_SUMMARIZATION_BASE_URL")
    override val openaiSummarizationModel: String? = namedModels.summarization?.modelId ?: configured("OPENAI_SUMMARIZATION_MODEL")
    override val openaiSummarizationParameters: String? = namedModels.summarization?.parameters?.let { parameters ->
        val defaults = configured("OPENAI_SUMMARIZATION_PARAMETERS")?.let {
            restJsonMapper.readValue<Map<String, Any?>>(it)
        }.orEmpty()
        restJsonMapper.writeValueAsString(defaults + restJsonMapper.readValue<Map<String, Any?>>(parameters))
    } ?: configured("OPENAI_SUMMARIZATION_PARAMETERS")
    override var regionProfile: String = normalizeRegion(configured(APP_LANGUAGE))
    override var activeAgentId: AgentId = AgentId.fromStorageValue(configured(ACTIVE_AGENT_ID))
    override var useFewShotExamples: Boolean = configured(USE_FEW_SHOTS)?.toBooleanStrictOrNull() ?: false
    override var useStreaming: Boolean = configured(USE_STREAMING)?.toBooleanStrictOrNull()
        ?: configured(USE_GRPC_LEGACY)?.toBooleanStrictOrNull()
        ?: false
    override var requestTimeoutMillis: Long =
        configured(REQUEST_TIMEOUT_MILLIS)?.toLongOrNull() ?: DEFAULT_REQUEST_TIMEOUT_MILLIS
    override var contextSize: Int =
        source.value(CONTEXT_SIZE, CONTEXT_SIZE)?.let { value ->
            requireNotNull(value.trim().toIntOrNull()?.takeIf { it >= 24_000 }) {
                "CONTEXT_SIZE must be an integer of at least 24000 tokens."
            }
        } ?: 125_000
    override var temperature: Float = configured(TEMPERATURE)?.toFloatOrNull() ?: 0.7f
    override var forbiddenFolders: List<String> = configured(FORBIDDEN_FOLDERS)
        ?.lines()
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?: DEFAULT_FORBIDDEN_FOLDERS
    override var embeddingsModel: EmbeddingsModel = configured(EMBEDDINGS_MODEL)
        ?.let { value ->
            EmbeddingsModel.entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.alias.equals(value, ignoreCase = true)
            }
        }
        ?: EmbeddingsModel.GigaEmbeddings
    override var codexAccessToken: String?
        get() = storedOrConfigured(CODEX_ACCESS_TOKEN)
        set(value) = putOrTombstone(CODEX_ACCESS_TOKEN, value)

    override var codexRefreshToken: String?
        get() = storedOrConfigured(CODEX_REFRESH_TOKEN)
        set(value) = putOrTombstone(CODEX_REFRESH_TOKEN, value)

    override var codexAccountId: String?
        get() = storedOrConfigured(CODEX_ACCOUNT_ID)
        set(value) = putOrTombstone(CODEX_ACCOUNT_ID, value)

    override var codexExpiresAt: Long?
        get() = storedOrConfigured(CODEX_EXPIRES_AT)?.toLongOrNull()
        set(value) = putOrTombstone(CODEX_EXPIRES_AT, value?.toString())

    override var gigaModel: LLMModel = configured(GIGA_MODEL)
        ?.let(::findLLMModel)
        ?.let { model -> llmBuildProfile.normalizeConfiguredModel(model, openaiModel) { defaultLlmModel() } }
        ?: defaultLlmModel()
        set(value) {
            field = llmBuildProfile.normalizeConfiguredModel(value, openaiModel) { defaultLlmModel() }
        }

    override fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String? {
        val key = agentId to model
        return promptOverrides[key] ?: configured("${SYSTEM_PROMPT}_${agentId.storageValue}_${model.name}")
    }

    override fun setSystemPromptForAgentModel(agentId: AgentId, model: LLMModel, prompt: String?) {
        val key = agentId to model
        if (prompt.isNullOrBlank()) {
            promptOverrides.remove(key)
        } else {
            promptOverrides[key] = prompt
        }
    }

    override fun hasKey(provider: LlmProvider): Boolean = when (provider) {
        LlmProvider.GIGA -> !gigaChatKey.isNullOrBlank()
        LlmProvider.QWEN -> !qwenChatKey.isNullOrBlank()
        LlmProvider.AI_TUNNEL -> !aiTunnelKey.isNullOrBlank()
        LlmProvider.ANTHROPIC -> !anthropicKey.isNullOrBlank()
        LlmProvider.OPENAI -> !openaiKey.isNullOrBlank()
        LlmProvider.CODEX -> !codexAccessToken.isNullOrBlank()
    }

    private fun configured(envKey: String, propertyKey: String = envKey): String? =
        source.value(envKey = envKey, propertyKey = propertyKey)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun storedOrConfigured(key: String): String? {
        val rejectedRefreshToken = preferenceStore.get(CODEX_REJECTED_DEPLOY_REFRESH_TOKEN)
        if (rejectedRefreshToken != null) {
            if (configured(CODEX_REFRESH_TOKEN).orEmpty() == rejectedRefreshToken) return null
            CODEX_CREDENTIAL_KEYS.forEach(preferenceStore::remove)
            preferenceStore.remove(CODEX_REJECTED_DEPLOY_REFRESH_TOKEN)
        }
        return when (val stored = preferenceStore.get(key)) {
            null -> configured(key)
            "" -> null
            else -> stored
        }
    }

    private fun putOrTombstone(key: String, value: String?) {
        if (value == null) {
            // Persist the rejected deployment token first so an interrupted tombstone stays recoverable.
            preferenceStore.put(CODEX_REJECTED_DEPLOY_REFRESH_TOKEN, configured(CODEX_REFRESH_TOKEN).orEmpty())
            preferenceStore.put(key, "")
        } else {
            preferenceStore.put(key, value)
            if (key == CODEX_ACCESS_TOKEN) preferenceStore.remove(CODEX_REJECTED_DEPLOY_REFRESH_TOKEN)
        }
    }

    private fun normalizeRegion(value: String?): String =
        if (value?.lowercase() == REGION_EN) REGION_EN else REGION_RU

    private fun defaultLlmModel(): LLMModel = llmBuildProfile.defaultConfiguredModel(::hasKey)

    private companion object {
        const val OPENAI_BASE_URL = "OPENAI_BASE_URL"
        const val OPENAI_MODEL = "OPENAI_MODEL"
        const val CODEX_ACCESS_TOKEN = "CODEX_ACCESS_TOKEN"
        const val CODEX_REFRESH_TOKEN = "CODEX_REFRESH_TOKEN"
        const val CODEX_ACCOUNT_ID = "CODEX_ACCOUNT_ID"
        const val CODEX_EXPIRES_AT = "CODEX_EXPIRES_AT"
        const val CODEX_REJECTED_DEPLOY_REFRESH_TOKEN = "CODEX_REJECTED_DEPLOY_REFRESH_TOKEN"
        const val APP_LANGUAGE = "APP_LANGUAGE"
        const val USE_FEW_SHOTS = "USE_FEW_SHOTS"
        const val USE_STREAMING = "USE_STREAMING"
        const val USE_GRPC_LEGACY = "USE_GRPC"
        const val SYSTEM_PROMPT = "SYSTEM_PROMPT"
        const val ACTIVE_AGENT_ID = "ACTIVE_AGENT_ID"
        const val GIGA_MODEL = "GIGA_MODEL"
        const val REQUEST_TIMEOUT_MILLIS = "REQUEST_TIMEOUT_MILLIS"
        const val CONTEXT_SIZE = "CONTEXT_SIZE"
        const val TEMPERATURE = "TEMPERATURE"
        const val FORBIDDEN_FOLDERS = "FORBIDDEN_FOLDERS"
        const val EMBEDDINGS_MODEL = "EMBEDDINGS_MODEL"
        val CODEX_CREDENTIAL_KEYS = listOf(
            CODEX_ACCESS_TOKEN,
            CODEX_REFRESH_TOKEN,
            CODEX_ACCOUNT_ID,
            CODEX_EXPIRES_AT,
        )
        val DEFAULT_FORBIDDEN_FOLDERS = listOf(
            "~/Library/",
            "~/.bash_history",
            "~/.zsh_history",
            "~/.zprofile",
            "~/.zshenv",
            "~/.bashprofile",
            "~/.ssh",
        )
    }
}
