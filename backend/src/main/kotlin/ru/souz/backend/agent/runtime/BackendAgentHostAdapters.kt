package ru.souz.backend.agent.runtime

import java.time.ZoneId
import java.util.Locale
import ru.souz.agent.AgentId
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.McpToolProvider
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.execution.model.AgentExecutionRuntimeConfig
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

/** Request-scoped backend settings wrapper used by the shared agent/runtime code. */
class BackendConversationSettingsProvider(
    private val delegate: SettingsProvider,
    private val defaultSystemPrompt: String,
    locale: String,
) : SettingsProvider by delegate {
    private var overrideSystemPrompt: String? = null

    override var defaultCalendar: String? = null
    override var regionProfile: String = localeToRegionProfile(locale)
    override var activeAgentId: AgentId = AgentId.default
    override var gigaModel: LLMModel = delegate.gigaModel
    override var useStreaming: Boolean = false
    override var contextSize: Int = delegate.contextSize
    override var temperature: Float = delegate.temperature

    override fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String =
        overrideSystemPrompt ?: defaultSystemPrompt

    override fun setSystemPromptForAgentModel(agentId: AgentId, model: LLMModel, prompt: String?) = Unit

    fun restore(
        activeAgentId: AgentId,
        temperature: Float,
        locale: String,
    ) {
        this.activeAgentId = activeAgentId
        this.temperature = temperature
        this.regionProfile = localeToRegionProfile(locale)
    }

    internal fun applyConfig(
        config: AgentExecutionRuntimeConfig,
        activeAgentId: AgentId,
        temperature: Float,
    ) {
        this.activeAgentId = activeAgentId
        this.gigaModel = parseModel(config.modelAlias) ?: delegate.gigaModel
        this.contextSize = config.contextSize
        this.temperature = config.temperature ?: temperature
        this.regionProfile = localeToRegionProfile(config.locale)
        this.overrideSystemPrompt = config.systemPrompt
        this.useStreaming = config.streamingMessages
    }

    internal fun applyRequest(
        request: BackendConversationTurnRequest,
        activeAgentId: AgentId,
        temperature: Float,
    ) = applyConfig(
        config = AgentExecutionRuntimeConfig(
            modelAlias = request.model,
            contextSize = request.contextSize,
            temperature = request.temperature,
            locale = request.locale,
            timeZone = request.timeZone,
            systemPrompt = request.systemPrompt,
            streamingMessages = request.streamingMessages == true,
            showToolEvents = false,
        ),
        activeAgentId = activeAgentId,
        temperature = temperature,
    )

    private fun parseModel(rawModel: String): LLMModel? =
        LLMModel.entries.firstOrNull { model ->
            model.alias.equals(rawModel, ignoreCase = true) || model.name.equals(rawModel, ignoreCase = true)
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

/** Backend fallback filter that leaves the bound tool catalog unchanged. */
object BackendNoopAgentToolsFilter : AgentToolsFilter {
    override fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
    ): Map<ToolCategory, Map<String, LLMToolSetup>> = toolsByCategory
}

/** Backend implementation for hosts without a meaningful default browser. */
object BackendNoopDefaultBrowserProvider : DefaultBrowserProvider {
    override fun defaultBrowserDisplayName(): String? = null
}

/** Backend implementation for hosts without MCP discovery. */
object BackendNoopMcpToolProvider : McpToolProvider {
    override suspend fun tools(): List<LLMToolSetup> = emptyList()
}

/** Backend-owned user-facing error text for shared agent failure paths. */
object BackendAgentErrorMessages : AgentErrorMessages {
    override suspend fun contextReset(): String = "Context was reset because it exceeded the allowed size."
    override suspend fun timeout(): String = "The model request timed out."
    override suspend fun noMoney(): String = "The configured provider has no available balance."
}

/** LLM API wrapper that keeps cumulative usage for one backend execution. */
class CumulativeUsageTrackingChatApi(
    private val delegate: LLMChatAPI,
    initialUsage: LLMResponse.Usage = LLMResponse.Usage(0, 0, 0, 0),
) : LLMChatAPI by delegate {
    private var cumulativeUsage: LLMResponse.Usage = initialUsage

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        val response = delegate.message(body)
        if (response is LLMResponse.Chat.Ok) {
            cumulativeUsage = cumulativeUsage.plus(response.usage)
        }
        return response
    }

    override suspend fun messageStream(body: LLMRequest.Chat): kotlinx.coroutines.flow.Flow<LLMResponse.Chat> =
        kotlinx.coroutines.flow.flow {
            var previousUsage = LLMResponse.Usage(0, 0, 0, 0)
            delegate.messageStream(body).collect { response ->
                if (response is LLMResponse.Chat.Ok) {
                    val delta = response.usage.deltaFrom(previousUsage)
                    cumulativeUsage = cumulativeUsage.plus(delta)
                    previousUsage = response.usage
                }
                emit(response)
            }
        }

    fun cumulativeUsage(): LLMResponse.Usage = cumulativeUsage
}

private fun LLMResponse.Usage.plus(other: LLMResponse.Usage): LLMResponse.Usage =
    LLMResponse.Usage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
        precachedTokens = precachedTokens + other.precachedTokens,
    )

private fun LLMResponse.Usage.deltaFrom(previous: LLMResponse.Usage): LLMResponse.Usage =
    LLMResponse.Usage(
        promptTokens = (promptTokens - previous.promptTokens).coerceAtLeast(0),
        completionTokens = (completionTokens - previous.completionTokens).coerceAtLeast(0),
        totalTokens = (totalTokens - previous.totalTokens).coerceAtLeast(0),
        precachedTokens = (precachedTokens - previous.precachedTokens).coerceAtLeast(0),
    )
