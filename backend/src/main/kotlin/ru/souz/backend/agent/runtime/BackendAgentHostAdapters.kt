package ru.souz.backend.agent.runtime

import java.time.ZoneId
import java.util.Locale
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

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

/** Backend implementation for hosts without desktop indexing. */
object BackendNoopAgentDesktopInfoRepository : AgentDesktopInfoRepository {
    override suspend fun search(query: String, limit: Int) = emptyList<ru.souz.db.StorredData>()
}

/** Backend fallback tool catalog used when no shared catalog is bound. */
object BackendNoopAgentToolCatalog : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = emptyMap()
}

/** Backend implementation for hosts without a meaningful default browser. */
object BackendNoopDefaultBrowserProvider : DefaultBrowserProvider {
    override fun defaultBrowserDisplayName(): String? = null
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
