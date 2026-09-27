package ru.souz.backend.agent.runtime

import java.util.Locale
import java.time.ZoneId
import ru.souz.agent.AgentId
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.memory.hindsight.UNSUPPORTED_MEMORY_MUTATION_NOTICE
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

/** Request-scoped backend settings wrapper used by the shared agent/runtime code. */
class BackendConversationSettingsProvider(
    private val delegate: SettingsProvider,
    private val defaultSystemPrompt: String,
    locale: String,
    useFewShotExamples: Boolean = delegate.useFewShotExamples,
    requestTimeoutMillis: Long = delegate.requestTimeoutMillis,
) : SettingsProvider by delegate {
    private var systemPrompt: String = defaultSystemPrompt

    override var defaultCalendar: String? = null
    override var regionProfile: String = localeToRegionProfile(locale)
    override var gigaModel: LLMModel = delegate.gigaModel
    override var useFewShotExamples: Boolean = useFewShotExamples
    override var useStreaming: Boolean = false
    override var requestTimeoutMillis: Long = requestTimeoutMillis
    override var contextSize: Int = delegate.contextSize
    override var temperature: Float = delegate.temperature

    override fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String = systemPrompt

    override fun setSystemPromptForAgentModel(agentId: AgentId, model: LLMModel, prompt: String?) = Unit

    fun restore(
        temperature: Float,
        locale: String,
    ) {
        this.temperature = temperature
        this.regionProfile = localeToRegionProfile(locale)
    }

    internal fun applyRequest(
        request: BackendConversationTurnRequest,
        temperature: Float,
    ) {
        this.gigaModel = request.model
        this.contextSize = request.contextSize
        this.temperature = request.temperature ?: temperature
        this.regionProfile = localeToRegionProfile(request.locale)
        this.systemPrompt = listOfNotNull(
            request.systemPrompt ?: defaultSystemPrompt,
            UNSUPPORTED_MEMORY_MUTATION_NOTICE.takeIf { request.clientToolsEnabled },
            when {
                !request.narrateSteps -> null
                regionProfile == SettingsProviderImpl.REGION_EN ->
                    "Before every tool call, write ONE short first-person sentence (max ~120 characters, " +
                        "plain text, no Markdown): what you just learned and what you are about to do. " +
                        "This is a status line, not the final answer."
                else ->
                    "Перед каждым вызовом инструментов напиши ОДНУ короткую фразу от первого лица " +
                        "(до ~120 символов, обычным текстом, без Markdown): что ты только что узнал и что " +
                        "собираешься сделать дальше. Это не финальный ответ, а статус."
            },
        ).joinToString("\n\n")
        this.useStreaming = request.streamingMessages == true
        this.useFewShotExamples = request.useFewShotExamples ?: this.useFewShotExamples
        this.requestTimeoutMillis = request.requestTimeoutMillis ?: this.requestTimeoutMillis
    }

    private fun localeToRegionProfile(locale: String): String {
        val language = runCatching { Locale.forLanguageTag(locale).language.lowercase() }
            .getOrDefault("")
        return if (language == SettingsProviderImpl.REGION_EN) {
            SettingsProviderImpl.REGION_EN
        } else {
            SettingsProviderImpl.REGION_RU
        }
    }
}

/** Backend runtime environment derived from one validated execution request. */
class BackendRequestRuntimeEnvironment(
    localeTag: String,
    timeZone: String,
) : AgentRuntimeEnvironment {
    override val locale: Locale = Locale.forLanguageTag(localeTag)
        .takeIf { it.language.isNotBlank() }
        ?: Locale.getDefault()

    override val zoneId: ZoneId = ZoneId.of(timeZone)
}

private object SettingsProviderImpl {
    const val REGION_RU = "ru"
    const val REGION_EN = "en"
}

/** Backend implementation for hosts without desktop indexing. */
object BackendNoopAgentDesktopInfoRepository : AgentDesktopInfoRepository {
    override suspend fun search(query: String, limit: Int) = emptyList<ru.souz.db.StorredData>()
}

/** Backend fallback tool catalog used when no shared catalog is bound. */
object BackendNoopAgentToolCatalog : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = emptyMap()
}

/** Backend-owned user-facing error text for shared agent failure paths. */
object BackendAgentErrorMessages : AgentErrorMessages {
    override suspend fun contextReset(): String = "Context was reset because it exceeded the allowed size."
    override suspend fun timeout(): String = "The model request timed out."
    override suspend fun noMoney(): String = "The configured provider has no available balance."
}
