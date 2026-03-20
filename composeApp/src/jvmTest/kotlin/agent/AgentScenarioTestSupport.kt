package agent

import giga.getHttpClient
import giga.getSessionTokenUsage
import io.ktor.client.plugins.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assumptions
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.Agent
import ru.souz.agent.AgentId
import ru.souz.agent.SystemPromptResolver
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.AgentSettings
import ru.souz.agent.impl.GraphBasedAgent
import ru.souz.agent.impl.LuaGraphBasedAgent
import ru.souz.db.ConfigStore
import ru.souz.db.DesktopInfoRepository
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl
import ru.souz.di.mainDiModule
import ru.souz.giga.GigaChatAPI
import ru.souz.giga.GigaModel
import ru.souz.giga.GigaRestChatAPI
import ru.souz.giga.LlmProvider
import ru.souz.giga.gigaJsonMapper
import ru.souz.llms.AiTunnelChatAPI
import ru.souz.llms.AnthropicChatAPI
import ru.souz.llms.OpenAIChatAPI
import ru.souz.llms.QwenChatAPI
import ru.souz.service.telegram.TelegramAuthState
import ru.souz.service.telegram.TelegramAuthStep
import ru.souz.service.telegram.TelegramService
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolsFactory
import ru.souz.tool.calendar.ToolCalendarCreateEvent
import ru.souz.tool.calendar.ToolCalendarDeleteEvent
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.files.ToolDeleteFile
import ru.souz.tool.files.ToolModifyFile
import ru.souz.tool.files.ToolMoveFile
import ru.souz.tool.files.ToolNewFile
import ru.souz.tool.mail.ToolMailReplyMessage
import ru.souz.tool.mail.ToolMailSendNewMessage
import ru.souz.tool.notes.ToolCreateNote
import ru.souz.tool.notes.ToolDeleteNote
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class AgentScenarioTestSupport(
    private val selectedModel: GigaModel,
    private val agentType: AgentId,
) {
    val filesUtil: FilesToolUtil by lazy { FilesToolUtil(spySettings) }

    private val spySettings: SettingsProviderImpl by lazy {
        spyk(SettingsProviderImpl(ConfigStore)) {
            every { forbiddenFolders } returns emptyList()
            every { useStreaming } returns false
            every { gigaModel } returns selectedModel
            every { requestTimeoutMillis } returns 60_000L
            every { temperature } returns 0.2f
            every { getSystemPromptForAgentModel(any(), any()) } answers {
                "Будь полезен. Выполняй инструкции с помощью тулов."
            }
        }
    }

    private var gigaRestChatAPI: GigaRestChatAPI? = null
    private var qwenChatAPI: QwenChatAPI? = null
    private var aiTunnelChatAPI: AiTunnelChatAPI? = null
    private var anthropicChatAPI: AnthropicChatAPI? = null
    private var openAiChatAPI: OpenAIChatAPI? = null
    private val httpRequestCount = AtomicLong(0)
    private val httpRequestTotalNanos = AtomicLong(0)

    private val testOverrideModule: DI.Module by lazy {
        DI.Module("TestOverrideModule") {
            bindSingleton<SettingsProvider>(overrides = true) { spySettings }
            bindSingleton<FilesToolUtil>(overrides = true) { filesUtil }
            bindSingleton<TelegramService>(overrides = true) {
                mockk<TelegramService>(relaxed = true).also { telegramService ->
                    every { telegramService.authState } returns MutableStateFlow(
                        TelegramAuthState(step = TelegramAuthStep.READY, isBusy = false)
                    )
                }
            }

            // Safe defaults: prevent accidental system mutations if a scenario doesn't explicitly mock these tools.
            bindSingleton<ToolNewFile>(overrides = true) {
                val tool = spyk(ToolNewFile(filesUtil))
                coEvery { tool.invoke(any<ToolNewFile.Input>()) } returns "Created"
                tool
            }
            bindSingleton<ToolModifyFile>(overrides = true) {
                val tool = spyk(ToolModifyFile(filesUtil))
                coEvery { tool.invoke(any<ToolModifyFile.Input>()) } returns "Modified"
                tool
            }
            bindSingleton<ToolDeleteFile>(overrides = true) {
                val tool = spyk(ToolDeleteFile(filesUtil))
                coEvery { tool.invoke(any<ToolDeleteFile.Input>()) } returns "Deleted"
                tool
            }
            bindSingleton<ToolMoveFile>(overrides = true) {
                val tool = spyk(ToolMoveFile(filesUtil))
                coEvery { tool.invoke(any<ToolMoveFile.Input>()) } returns "Moved"
                tool
            }
            bindSingleton<ToolCreateNote>(overrides = true) {
                val tool = spyk(ToolCreateNote(ToolRunBashCommand))
                coEvery { tool.invoke(any<ToolCreateNote.Input>()) } returns "Created"
                tool
            }
            bindSingleton<ToolDeleteNote>(overrides = true) {
                val tool = spyk(ToolDeleteNote(ToolRunBashCommand))
                coEvery { tool.invoke(any<ToolDeleteNote.Input>()) } returns "Deleted"
                tool
            }
            bindSingleton<ToolCalendarCreateEvent>(overrides = true) {
                val tool = spyk(ToolCalendarCreateEvent(ToolRunBashCommand))
                coEvery { tool.invoke(any<ToolCalendarCreateEvent.Input>()) } returns "Event created"
                tool
            }
            bindSingleton<ToolCalendarDeleteEvent>(overrides = true) {
                val tool = spyk(ToolCalendarDeleteEvent(ToolRunBashCommand))
                coEvery { tool.invoke(any<ToolCalendarDeleteEvent.Input>()) } returns "Deleted"
                tool
            }
            bindSingleton<ToolMailSendNewMessage>(overrides = true) {
                val tool = spyk(ToolMailSendNewMessage(ToolRunBashCommand))
                coEvery { tool.invoke(any<ToolMailSendNewMessage.Input>()) } returns "Sent"
                tool
            }
            bindSingleton<ToolMailReplyMessage>(overrides = true) {
                val tool = spyk(ToolMailReplyMessage(ToolRunBashCommand))
                coEvery { tool.invoke(any<ToolMailReplyMessage.Input>()) } returns "Replied"
                tool
            }

            bindSingleton<GigaRestChatAPI>(overrides = true) {
                if (gigaRestChatAPI == null) {
                    gigaRestChatAPI = GigaRestChatAPI(instance(), instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                gigaRestChatAPI!!
            }
            bindSingleton<QwenChatAPI>(overrides = true) {
                if (qwenChatAPI == null) {
                    qwenChatAPI = QwenChatAPI(instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                qwenChatAPI!!
            }
            bindSingleton<AiTunnelChatAPI>(overrides = true) {
                if (aiTunnelChatAPI == null) {
                    aiTunnelChatAPI = AiTunnelChatAPI(instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                aiTunnelChatAPI!!
            }
            bindSingleton<AnthropicChatAPI>(overrides = true) {
                if (anthropicChatAPI == null) {
                    anthropicChatAPI = AnthropicChatAPI(instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                anthropicChatAPI!!
            }
            bindSingleton<OpenAIChatAPI>(overrides = true) {
                if (openAiChatAPI == null) {
                    openAiChatAPI = OpenAIChatAPI(instance(), instance()).apply {
                        getHttpClient().plugin(HttpSend).intercept { request ->
                            val startNanos = System.nanoTime()
                            try {
                                execute(request)
                            } finally {
                                httpRequestCount.incrementAndGet()
                                httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                            }
                        }
                    }
                }
                openAiChatAPI!!
            }
            bindSingleton<GigaChatAPI>(overrides = true) {
                when (selectedModel.provider) {
                    LlmProvider.GIGA -> instance<GigaRestChatAPI>()
                    LlmProvider.QWEN -> instance<QwenChatAPI>()
                    LlmProvider.AI_TUNNEL -> instance<AiTunnelChatAPI>()
                    LlmProvider.ANTHROPIC -> instance<AnthropicChatAPI>()
                    LlmProvider.OPENAI -> instance<OpenAIChatAPI>()
                }
            }
            bindSingleton<DesktopInfoRepository>(overrides = true) {
                val repository = DesktopInfoRepository(instance(), instance(), instance(), instance())
                spyk(repository) { coEvery { search(any(), any()) } returns emptyList() }
            }
        }
    }

    fun runTest(
        block: suspend TestScope.() -> Unit,
    ) = kotlinx.coroutines.test.runTest(timeout = DEFAULT_TEST_TIMEOUT, testBody = block)

    fun checkEnvironment() {
        val apiKeyName = when (selectedModel.provider) {
            LlmProvider.GIGA -> "GIGA_KEY"
            LlmProvider.QWEN -> "QWEN_KEY"
            LlmProvider.AI_TUNNEL -> "AITUNNEL_KEY"
            LlmProvider.ANTHROPIC -> "ANTHROPIC_API_KEY"
            LlmProvider.OPENAI -> "OPENAI_API_KEY"
        }
        val apiKey = System.getenv(apiKeyName) ?: System.getProperty(apiKeyName)
        Assumptions.assumeTrue(
            !apiKey.isNullOrBlank(),
            "Skipping integration tests: $apiKeyName is not set (selected model=${selectedModel.alias})"
        )
    }

    suspend fun runScenarioWithMocks(
        userPrompt: String,
        overrides: DI.MainBuilder.() -> Unit,
    ) {
        val di = DI.invoke(allowSilentOverride = true) {
            import(mainDiModule)
            import(testOverrideModule, allowOverride = true)
            bindProvider<DI> { this.di }
            overrides()
        }
        val agent = when (agentType) {
            AgentId.GRAPH -> GraphBasedAgent(di, gigaJsonMapper)
            AgentId.LUA_GRAPH -> LuaGraphBasedAgent(di, gigaJsonMapper)
        }
        runGraphAgent(agent, di, userPrompt)
    }

    fun finish() {
        when (selectedModel.provider) {
            LlmProvider.GIGA -> println("Spent: ${gigaRestChatAPI?.getSessionTokenUsage() ?: "n/a"}")
            LlmProvider.QWEN -> println("Spent: ${qwenChatAPI?.getSessionTokenUsage() ?: "n/a"}")
            LlmProvider.AI_TUNNEL -> println("Spent: ${aiTunnelChatAPI?.getSessionTokenUsage() ?: "n/a"}")
            LlmProvider.ANTHROPIC -> println("Spent: ${anthropicChatAPI?.getSessionTokenUsage() ?: "n/a"}")
            LlmProvider.OPENAI -> println("Spent: ${openAiChatAPI?.getSessionTokenUsage() ?: "n/a"}")
        }
        val requestCount = httpRequestCount.get()
        if (requestCount == 0L) {
            println("HTTP requests: 0")
            return
        }
        val avgMs = httpRequestTotalNanos.get().toDouble() / requestCount / 1_000_000.0
        println("HTTP requests: $requestCount, avg/request: ${"%.2f".format(avgMs)} ms")
    }

    private suspend fun runGraphAgent(agent: Agent, di: DI, userPrompt: String) {
        val settingsProvider: SettingsProvider = di.direct.instance()
        val toolsFactory: ToolsFactory = di.direct.instance()
        val systemPromptResolver: SystemPromptResolver = di.direct.instance()

        val model = settingsProvider.gigaModel
        val settings = AgentSettings(
            model = model.alias,
            temperature = settingsProvider.temperature,
            toolsByCategory = toolsFactory.toolsByCategory,
            contextSize = settingsProvider.contextSize,
        )
        val prompt = settingsProvider.getSystemPromptForAgentModel(agentType, model)
            ?: systemPromptResolver.defaultPrompt(
                agentId = agentType,
                model = model,
                regionProfile = settingsProvider.regionProfile,
            )
        val ctx = AgentContext(
            input = userPrompt,
            settings = settings,
            history = emptyList(),
            activeTools = settings.tools.byName.values.map { it.fn },
            systemPrompt = prompt,
        )

        agent.execute(ctx)
    }
}

private val DEFAULT_TEST_TIMEOUT: Duration = 5.minutes
