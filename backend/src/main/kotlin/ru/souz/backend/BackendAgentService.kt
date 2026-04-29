package ru.souz.backend

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.LinkedHashSet
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.AgentContextFactory
import ru.souz.agent.AgentExecutor
import ru.souz.agent.agentDiModule
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.McpToolProvider
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.runtime.ApiClassifier
import ru.souz.tool.LocalRegexClassifier
import ru.souz.tool.UserMessageClassifier

class BackendAgentService(
    private val baseSettingsProvider: SettingsProvider,
    private val llmApiFactory: (BackendRequestSettingsProvider) -> LLMChatAPI,
    private val sessionRepository: AgentSessionRepository,
    private val logObjectMapper: ObjectMapper,
    private val agentConversationExists: suspend (
        userId: String,
        conversationId: String,
    ) -> Boolean = { _, _ -> true },
) {
    private val agentMutex = Mutex()
    private val activeAgentRequestIds = LinkedHashSet<String>()
    private val activeAgentConversations = LinkedHashSet<AgentConversationKey>()
    private val completedAgentRequestIds = LinkedHashSet<String>()

    suspend fun sendAgentRequest(request: AgentRequest): AgentResponse {
        val validated = request.validated()
        if (!agentConversationExists(validated.userId, validated.conversationId)) {
            throw BackendRequestException(404, "User or conversation not found.")
        }
        val conversationKey = AgentConversationKey(validated.userId, validated.conversationId)
        agentMutex.withLock {
            when {
                validated.requestId in activeAgentRequestIds || validated.requestId in completedAgentRequestIds ->
                    throw BackendRequestException(409, "Duplicate requestId.")

                conversationKey in activeAgentConversations ->
                    throw BackendRequestException(409, "Conversation already has an active request.")
            }

            activeAgentRequestIds += validated.requestId
            activeAgentConversations += conversationKey
        }

        try {
            val session = sessionRepository.load(conversationKey)
            val requestSettings = BackendRequestSettingsProvider(
                delegate = baseSettingsProvider,
                model = validated.model,
                contextSize = validated.contextSize,
                temperature = session?.context?.settings?.temperature ?: baseSettingsProvider.temperature,
                locale = validated.locale,
            )
            if (session != null) {
                requestSettings.activeAgentId = session.activeAgentId
            }

            val usageTrackingApi = UsageTrackingChatApi(llmApiFactory(requestSettings))
            val requestDi = requestDi(requestSettings, usageTrackingApi)
            val contextFactory: AgentContextFactory = requestDi.direct.instance()
            val executor: AgentExecutor = requestDi.direct.instance()

            val previousContext = session?.context ?: contextFactory.create(requestSettings.activeAgentId)
            val seedContext = previousContext.copy(
                settings = previousContext.settings.copy(
                    model = requestSettings.gigaModel.alias,
                    contextSize = validated.contextSize,
                ),
                systemPrompt = contextFactory.systemPromptFor(requestSettings.activeAgentId, requestSettings.gigaModel),
            )

            val result = executor.execute(
                agentId = requestSettings.activeAgentId,
                context = seedContext,
                input = validated.prompt,
            )
            val usage = usageTrackingApi.latestUsage()

            sessionRepository.save(
                conversationKey,
                AgentConversationSession(
                    activeAgentId = requestSettings.activeAgentId,
                    context = result.context,
                    locale = validated.locale,
                    timeZone = validated.timeZone,
                )
            )
            rememberCompletedAgentRequestId(validated.requestId)

            return AgentResponse(
                requestId = validated.requestId,
                conversationId = validated.conversationId,
                userMessageId = UUID.randomUUID().toString(),
                assistantMessageId = UUID.randomUUID().toString(),
                content = result.output,
                model = validated.model,
                provider = providerForModel(validated.model, fallback = baseSettingsProvider.gigaModel.provider),
                contextSize = validated.contextSize,
                usage = AgentUsage(
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    totalTokens = usage.totalTokens,
                    precachedTokens = usage.precachedTokens,
                ),
            )
        } finally {
            agentMutex.withLock {
                activeAgentRequestIds -= validated.requestId
                activeAgentConversations -= conversationKey
            }
        }
    }

    private fun requestDi(
        requestSettings: BackendRequestSettingsProvider,
        usageTrackingApi: UsageTrackingChatApi,
    ): DI = DI {
        bindSingleton(tag = TAG_LOG) { logObjectMapper }
        bindSingleton<AgentSettingsProvider> { requestSettings }
        bindSingleton<AgentDesktopInfoRepository> { BackendNoopAgentDesktopInfoRepository }
        bindSingleton<AgentToolCatalog> { BackendNoopAgentToolCatalog }
        bindSingleton<AgentToolsFilter> { BackendNoopAgentToolsFilter }
        bindSingleton<DefaultBrowserProvider> { BackendNoopDefaultBrowserProvider }
        bindSingleton<McpToolProvider> { BackendNoopMcpToolProvider }
        bindSingleton<AgentTelemetry> { BackendNoopAgentTelemetry }
        bindSingleton<AgentErrorMessages> { BackendAgentErrorMessages }
        bindSingleton<LLMChatAPI> { usageTrackingApi }
        bindSingleton(tag = TAG_API) { ApiClassifier(usageTrackingApi) }
        bindSingleton<UserMessageClassifier>(tag = TAG_LOCAL) { LocalRegexClassifier }
        import(
            agentDiModule(
                logObjectMapperTag = TAG_LOG,
                apiClassifierTag = TAG_API,
                localClassifierTag = TAG_LOCAL,
            )
        )
    }

    private fun rememberCompletedAgentRequestId(requestId: String) {
        completedAgentRequestIds += requestId
        while (completedAgentRequestIds.size > MAX_COMPLETED_AGENT_REQUEST_IDS) {
            val oldestRequestId = completedAgentRequestIds.iterator().next()
            completedAgentRequestIds -= oldestRequestId
        }
    }

    private fun providerForModel(model: String, fallback: LlmProvider): String =
        LLMModel.entries.firstOrNull { candidate ->
            candidate.alias.equals(model, ignoreCase = true) || candidate.name.equals(model, ignoreCase = true)
        }?.provider?.name ?: fallback.name

    private companion object {
        const val MAX_COMPLETED_AGENT_REQUEST_IDS = 10_000
        const val TAG_LOG = "backendAgentLog"
        const val TAG_API = "backendAgentApiClassifier"
        const val TAG_LOCAL = "backendAgentLocalClassifier"
    }
}
