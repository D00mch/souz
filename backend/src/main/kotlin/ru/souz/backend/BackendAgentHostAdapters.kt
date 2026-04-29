package ru.souz.backend

import java.util.Locale
import ru.souz.agent.AgentId
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.McpToolProvider
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

class BackendConversationSettingsProvider(
    private val delegate: SettingsProvider,
    private val systemPrompt: String,
    locale: String,
) : SettingsProvider by delegate {
    override var defaultCalendar: String? = null
    override var regionProfile: String = localeToRegionProfile(locale)
    override var activeAgentId: AgentId = AgentId.default
    override var gigaModel: LLMModel = delegate.gigaModel
    override var useStreaming: Boolean = false
    override var contextSize: Int = delegate.contextSize
    override var temperature: Float = delegate.temperature

    override fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String = systemPrompt

    override fun setSystemPromptForAgentModel(agentId: AgentId, model: LLMModel, prompt: String?) = Unit

    fun restore(
        activeAgentId: AgentId,
        contextSize: Int,
        temperature: Float,
        locale: String,
    ) {
        this.activeAgentId = activeAgentId
        this.contextSize = contextSize
        this.temperature = temperature
        this.regionProfile = localeToRegionProfile(locale)
    }

    internal fun applyRequest(
        request: ValidatedAgentRequest,
        activeAgentId: AgentId,
        temperature: Float,
    ) {
        this.activeAgentId = activeAgentId
        this.gigaModel = parseModel(request.model) ?: delegate.gigaModel
        this.contextSize = request.contextSize
        this.temperature = temperature
        this.regionProfile = localeToRegionProfile(request.locale)
    }

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

private object SettingsProviderImpl {
    const val REGION_RU = "ru"
    const val REGION_EN = "en"
}

object BackendNoopAgentDesktopInfoRepository : AgentDesktopInfoRepository {
    override suspend fun search(query: String, limit: Int) = emptyList<ru.souz.db.StorredData>()
}

object BackendNoopAgentToolCatalog : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = emptyMap()
}

object BackendNoopAgentToolsFilter : AgentToolsFilter {
    override fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
    ): Map<ToolCategory, Map<String, LLMToolSetup>> = toolsByCategory
}

object BackendNoopDefaultBrowserProvider : DefaultBrowserProvider {
    override fun defaultBrowserDisplayName(): String? = null
}

object BackendNoopMcpToolProvider : McpToolProvider {
    override suspend fun tools(): List<LLMToolSetup> = emptyList()
}

object BackendNoopAgentTelemetry : AgentTelemetry {
    override fun recordToolExecution(
        functionName: String,
        functionArguments: Map<String, Any>,
        toolCategory: ToolCategory?,
        durationMs: Long,
        success: Boolean,
        errorMessage: String?,
    ) = Unit
}

object BackendAgentErrorMessages : AgentErrorMessages {
    override suspend fun contextReset(): String = "Context was reset because it exceeded the allowed size."
    override suspend fun timeout(): String = "The model request timed out."
    override suspend fun noMoney(): String = "The configured provider has no available balance."
}

class UsageTrackingChatApi(private val delegate: LLMChatAPI) : LLMChatAPI by delegate {
    @Volatile
    private var latestUsage: LLMResponse.Usage = LLMResponse.Usage(0, 0, 0, 0)

    fun resetUsage() {
        latestUsage = LLMResponse.Usage(0, 0, 0, 0)
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        val response = delegate.message(body)
        if (response is LLMResponse.Chat.Ok) {
            latestUsage = response.usage
        }
        return response
    }

    override suspend fun messageStream(body: LLMRequest.Chat): kotlinx.coroutines.flow.Flow<LLMResponse.Chat> =
        kotlinx.coroutines.flow.flow {
            delegate.messageStream(body).collect { response ->
                if (response is LLMResponse.Chat.Ok) {
                    latestUsage = response.usage
                }
                emit(response)
            }
        }

    fun latestUsage(): LLMResponse.Usage = latestUsage
}
