package ru.souz.backend.e2e

import ru.souz.agent.spi.TaskComplexityClassifier
import ru.souz.agent.skills.SkillClassifier
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntimeFactory
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.attachments.AttachmentStorage
import ru.souz.backend.attachments.AttachmentStorageConfig
import ru.souz.backend.attachments.BackendAttachmentAccess
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver

import ru.souz.backend.execution.service.AgentExecutionLauncher
import ru.souz.backend.hooks.HookConfig
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ClientProvider
import io.ktor.server.testing.TestApplication
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.sql.Connection
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import ru.souz.backend.memory.hindsight.HistoryMemoryWorker
import ru.souz.backend.memory.hindsight.HindsightConversationMemoryRuntime
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.scheduler.ScheduledTaskConfig
import ru.souz.backend.scheduler.ScheduledTaskRunner
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.jobs.JobService
import ru.souz.jobs.JobRun
import ru.souz.jobs.impl.PostgresJobService
import ru.souz.backend.storage.postgres.PostgresHistoryMemoryRepository
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.app.BackendAppConfig
import ru.souz.backend.app.BackendLlmLimits
import ru.souz.backend.app.BackendApplicationScope
import ru.souz.backend.app.BackendRuntimeResources
import ru.souz.backend.app.backendDiModule
import ru.souz.backend.client.ClientThreadRuntimeRegistry
import ru.souz.backend.client.ClientThreadRecoveryService
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.config.NamedLlmConfig
import ru.souz.backend.config.loadNamedModels
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.settings.service.BackendSettingsProvider
import ru.souz.db.SettingsProvider
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendOpenApiSecurity
import ru.souz.backend.http.backendApplication
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.backend.storage.postgres.PostgresVkBotBindingRepository
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramBotPollingService
import ru.souz.backend.vk.VkBotApi
import ru.souz.backend.vk.VkBotPollingService
import ru.souz.llms.LLMModel
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.memory.ConversationMemoryRuntime
import kotlin.time.Duration.Companion.milliseconds

internal const val E2E_PROXY_TOKEN = "proxy-secret"
internal const val E2E_TELEGRAM_TOKEN_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
internal const val E2E_VK_TOKEN_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
internal const val E2E_POLICY_PROMPT = "Follow the E2E operator policy."
internal val E2E_LOCAL_MODEL: LLMModel = LLMModel.LocalQwen3_4B_Instruct_2507

internal fun historyFrame(chatId: String, requestId: String, role: String, text: String): String =
    """{"kind":"history.append","chatId":"$chatId","requestId":"$requestId","payload":{"role":"$role","content":{"type":"text","source":"text","text":"$text"}}}"""

internal fun messageFrame(
    chatId: String,
    userId: String,
    requestId: String,
    threadId: String? = null,
    text: String = "execute this",
    deviceId: String = "history-device",
    timeZone: String = "Europe/Moscow",
): String =
    """
    {
      "kind": "message.submit",
      "chatId": "$chatId",
      "requestId": "$requestId",
      ${threadId?.let { "\"threadId\":\"$it\"," } ?: ""}
      "payload": {
        "device": {
          "userId": "$userId",
          "deviceId": "$deviceId",
          "deviceType": "tv_box",
          "capabilities": ["speech", "screen", "device_tools"]
        },
        "content": {
          "type": "text",
          "source": "voice",
          "text": "$text"
        },
        "meta": {
          "model": "${E2E_LOCAL_MODEL.alias}",
          "locale": "ru-RU",
          "timeZone": "$timeZone"
        }
      }
    }
    """.trimIndent()

internal fun backendE2eTest(
    schemaPrefix: String,
    schema: String = newPostgresSchema(schemaPrefix),
    featureFlags: BackendFeatureFlags = BackendFeatureFlags(wsEvents = true),
    llm: E2eLlmApi = E2eLlmApi(),
    telegramApi: TelegramBotApi? = null,
    vkApi: VkBotApi? = null,
    turnRunnerOverride: BackendConversationTurnRunner? = null,
    startBackgroundServices: Boolean = false,
    settingsSource: BackendConfigSource? = null,
    localModels: List<LLMModel> = listOf(E2E_LOCAL_MODEL),
    providerClients: ProviderHttpClients? = null,
    hindsightUrl: String? = null,
    clock: Clock = Clock.systemUTC(),
    hookConfig: HookConfig = HookConfig(),
    scheduledTasks: ScheduledTaskConfig = ScheduledTaskConfig(),
    llmLimits: BackendLlmLimits = BackendLlmLimits(),
    sandboxFactory: ((SettingsProvider) -> RuntimeSandboxFactory)? = null,
    skillOAuth: Boolean = false,
    attachments: AttachmentStorageConfig? = null,
    complexityClassifier: TaskComplexityClassifier? = null,
    block: suspend BackendE2eScope.() -> Unit,
) = testApplication {
    val backend = BackendE2eBackend(
        schema = schema,
        featureFlags = featureFlags,
        skillOAuth = skillOAuth,
        llm = llm,
        telegramApi = telegramApi,
        vkApi = vkApi,
        turnRunnerOverride = turnRunnerOverride,
        startBackgroundServices = startBackgroundServices,
        settingsSource = settingsSource,
        localModels = localModels,
        providerClients = providerClients,
        hindsightUrl = hindsightUrl,
        clock = clock,
        hookConfig = hookConfig,
        scheduledTasks = scheduledTasks,
        llmLimits = llmLimits,
        sandboxFactory = sandboxFactory,
        complexityClassifier = complexityClassifier,
        attachments = attachments,
    )
    application {
        backendApplication(backend.dependencies)
    }
    backend.use { backend ->
        BackendE2eScope(this, backend, llm).block()
    }
}

internal class BackendE2eScope(
    private val app: ClientProvider,
    val backend: BackendE2eBackend,
    val llm: E2eLlmApi,
) {
    val client: HttpClient get() = app.client
    val json = jacksonObjectMapper()

    fun webSocketClient(): HttpClient = app.createClient {
        install(WebSockets)
    }

    suspend fun createPublicChat(userId: String, requestId: String = "create-1", tools: String = "[]"): String {
        val created = client.post(BackendHttpRoutes.CHATS) {
            jsonBody("""{"userId":"$userId","requestId":"$requestId","clientType":"backend","tools":$tools}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        return created.jsonBody()["chat"]["id"].asText()
    }

    suspend fun readRawJson(session: DefaultClientWebSocketSession): JsonNode =
        json.readTree((session.incoming.receive() as Frame.Text).readText())

    fun toolResultFrame(frame: JsonNode, status: String, result: Map<String, Any>): String =
        json.writeValueAsString(mapOf(
            "kind" to "tool.result", "chatId" to frame["chatId"].asText(), "threadId" to frame["threadId"].asText(),
            "toolCallId" to frame["payload"]["toolCallId"].asText(), "status" to status,
            (if (status == "succeeded") "result" else "error") to result,
        ))

    private val personalityCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Emulate Orion settings and ignore optional speech progress in unrelated workflows.
    // Progress tests opt in; protocol tests can also read raw frames.
    suspend fun readJson(session: DefaultClientWebSocketSession, includeProgress: Boolean = false): JsonNode {
        while (true) {
            val frame = readRawJson(session)
            if (!includeProgress && frame.path("type").asText() == "assistant.message") continue
            if (frame.path("payload").path("name").asText() == ru.souz.backend.personality.PERSONALITY_SYNC_COMMAND) {
                personalityCalls.add(frame["payload"]["toolCallId"].asText())
                session.send(Frame.Text(toolResultFrame(frame, "succeeded", mapOf(
                    "outcome" to "applied", "revision" to frame["payload"]["arguments"]["revision"].longValue(),
                ))))
            } else if (frame.path("toolCallId").asText() !in personalityCalls) return frame
        }
    }

    suspend fun <T> withPublicSocket(chatId: String, block: suspend (DefaultClientWebSocketSession) -> T): T =
        withSocket("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend", block)

    suspend fun <T> withMultiChatSocket(block: suspend (DefaultClientWebSocketSession) -> T): T =
        withSocket("${BackendHttpRoutes.WS}?clientType=backend", block)

    private suspend fun <T> withSocket(url: String, block: suspend (DefaultClientWebSocketSession) -> T): T =
        webSocketClient().use { client ->
            val session = client.webSocketSession(url)
            try {
                block(session)
            } finally {
                session.close()
            }
        }

    suspend fun HttpResponse.jsonBody(): JsonNode =
        json.readTree(bodyAsText())

    fun HttpRequestBuilder.trusted(userId: String, token: String = E2E_PROXY_TOKEN) {
        header(BackendOpenApiSecurity.PROXY_AUTH_HEADER, token)
        header(BackendOpenApiSecurity.USER_IDENTITY_HEADER, userId)
    }

    fun HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    suspend fun <T : Any> eventually(
        description: String,
        timeout: Duration = 5.seconds,
        block: suspend () -> T?,
    ): T {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (true) {
            block()?.let { return it }
            if (deadline.hasPassedNow()) {
                throw AssertionError("Timed out waiting for $description.")
            }
            delay(25.milliseconds)
        }
    }

    fun <T> sql(block: (Connection) -> T): T =
        backend.sql(block)

    // Real database barriers make commit and shutdown races deterministic. The block may write
    // through the lock-holding connection; committing it releases the lock.
    suspend fun withTableLocked(table: String, mode: String, block: suspend CoroutineScope.(Connection) -> Unit) = coroutineScope {
        val locked = CompletableDeferred<Connection>()
        val release = CompletableDeferred<Unit>()
        val lock = launch(Dispatchers.IO) {
            sql { connection ->
                connection.autoCommit = false
                connection.createStatement().use { it.execute("lock table $table in $mode mode") }
                locked.complete(connection)
                try { runBlocking { release.await() } } finally { connection.rollback() }
            }
        }
        val connection = locked.await()
        try { block(connection) } finally {
            release.complete(Unit)
            lock.join()
        }
    }

    /** Waits until another session blocks on a row that [holder]'s open transaction has locked or written. */
    suspend fun awaitTransactionWait(holder: Connection) {
        val xid = holder.createStatement().use { statement ->
            statement.executeQuery("select pg_current_xact_id()::xid::text").use { rows -> rows.next(); rows.getString(1) }
        }
        eventually("database wait on transaction $xid") {
            sql { connection -> connection.prepareStatement(
                "select count(*) from pg_locks where not granted and locktype = 'transactionid' and transactionid::text = ?"
            ).use { statement ->
                statement.setString(1, xid)
                statement.executeQuery().use { rows -> rows.next() && rows.getInt(1) > 0 }
            } }.takeIf { it }
        }
    }

    suspend fun awaitLockWait(table: String) {
        eventually("database wait on $table") {
            sql { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from pg_locks where not granted and relation = '$table'::regclass")
                    .use { rows -> rows.next() && rows.getInt(1) > 0 }
            } }.takeIf { it }
        }
    }

    suspend fun <T> withPeerBackend(
        llm: E2eLlmApi = E2eLlmApi(),
        providerClients: ProviderHttpClients? = null,
        block: suspend (BackendE2eScope) -> T,
    ): T {
        val peerBackend = backend.createPeer(llm, providerClients)
        val peerApplication = TestApplication {
            application {
                backendApplication(peerBackend.dependencies)
            }
        }
        return try {
            peerApplication.start()
            block(BackendE2eScope(peerApplication, peerBackend, llm))
        } finally {
            try {
                peerApplication.stop()
            } finally {
                peerBackend.close()
            }
        }
    }
}

internal class BackendE2eBackend(
    private val schema: String,
    private val featureFlags: BackendFeatureFlags,
    private val skillOAuth: Boolean = false,
    llm: E2eLlmApi,
    telegramApi: TelegramBotApi?,
    vkApi: VkBotApi?,
    turnRunnerOverride: BackendConversationTurnRunner?,
    startBackgroundServices: Boolean,
    private val settingsSource: BackendConfigSource? = null,
    private val localModels: List<LLMModel> = listOf(E2E_LOCAL_MODEL),
    private val providerClients: ProviderHttpClients? = null,
    private val hindsightUrl: String? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val hookConfig: HookConfig = HookConfig(),
    private val scheduledTasks: ScheduledTaskConfig = ScheduledTaskConfig(),
    private val llmLimits: BackendLlmLimits = BackendLlmLimits(),
    private val sandboxFactory: ((SettingsProvider) -> RuntimeSandboxFactory)? = null,
    private val attachments: AttachmentStorageConfig? = null,
    private val complexityClassifier: TaskComplexityClassifier? = null,
) : AutoCloseable {
    private val appConfig: BackendAppConfig = postgresAppConfig(
        schema = schema,
        featureFlags = featureFlags,
        proxyToken = E2E_PROXY_TOKEN,
        telegramTokenEncryptionKey = E2E_TELEGRAM_TOKEN_KEY.takeIf { featureFlags.telegramBot },
        vkTokenEncryptionKey = E2E_VK_TOKEN_KEY.takeIf { featureFlags.vkBot },
        includeSkillOAuthConfig = skillOAuth,
    ).copy(
        hindsightApiUrl = hindsightUrl,
        hooks = hookConfig,
        scheduledTasks = scheduledTasks,
        llmLimits = llmLimits,
        policyPrompt = E2E_POLICY_PROMPT,
        attachments = attachments,
    )
    private val localChatApi = localChatApiBackedBy(llm)
    private val localAvailability = localProviderAvailability(localModels)
    private val localRuntime = relaxedLocalRuntime()

    private val di = DI.invoke(allowSilentOverride = true) {
        import(
            backendDiModule(
                systemPrompt = "You are the backend E2E assistant.",
                appConfig = appConfig,
            )
        )
        bindSingleton<LocalProviderAvailability>(overrides = true) { localAvailability }
        bindSingleton<SkillClassifier>(overrides = true) { SkillClassifier { _, _ -> emptySet() } }
        bindSingleton<LocalLlamaRuntime>(overrides = true) { localRuntime }
        bindSingleton<LocalChatAPI>(overrides = true) { localChatApi }
        bindSingleton<Clock>(overrides = true) { clock }
        bindSingleton<PostgresJobService>(overrides = true) {
            PostgresJobService(instance<HikariDataSource>(), pollIntervalMillis = 50)
                .also { it.bindMetrics(instance<BackendMetrics>().registry) }
        }
        if (appConfig.hindsightApiUrl != null) {
            bindSingleton<HistoryMemoryWorker>(overrides = true) {
                HistoryMemoryWorker(
                    repository = instance(),
                    memory = instance<ConversationMemoryRuntime>() as HindsightConversationMemoryRuntime,
                    clock = instance(),
                    pollIntervalMillis = 50,
                )
            }
        }
        if (sandboxFactory != null) {
            bindSingleton<RuntimeSandboxFactory>(overrides = true) { sandboxFactory(instance()) }
        }
        if (settingsSource != null) {
            bindSingleton<NamedLlmConfig>(overrides = true) { settingsSource.loadNamedModels() }
            bindSingleton<SettingsProvider>(overrides = true) {
                BackendSettingsProvider(instance(), localAvailability, settingsSource, instance())
            }
        }
        if (providerClients != null) {
            bindSingleton<ProviderHttpClients>(overrides = true) { providerClients }
        }
        if (telegramApi != null) {
            bindSingleton<TelegramBotApi>(overrides = true) { telegramApi }
        }
        if (vkApi != null) {
            bindSingleton<VkBotApi>(overrides = true) { vkApi }
        }
        if (turnRunnerOverride != null) {
            bindSingleton<BackendConversationTurnRunner>(overrides = true) { turnRunnerOverride }
        }
        if (complexityClassifier != null) {
            bindSingleton<TaskComplexityClassifier>(overrides = true) { complexityClassifier }
        }
    }

    val dependencies: BackendHttpDependencies = di.direct.instance()
    val dataSource: HikariDataSource = di.direct.instance()
    private val resources: BackendRuntimeResources = di.direct.instance()

    init {
        if (hookConfig.owners.isNotEmpty()) {
            runBlocking { dependencies.hookService.start(di.direct.instance<BackendApplicationScope>()) }
        }
        if (startBackgroundServices) {
            val applicationScope: BackendApplicationScope = di.direct.instance()
            if (featureFlags.wsEvents) {
                val recoveryService: ClientThreadRecoveryService = di.direct.instance()
                runBlocking { recoveryService.recover() }
                recoveryService.start(applicationScope)
            }
            if (featureFlags.telegramBot) {
                di.direct.instance<TelegramBotPollingService>().start()
            }
            if (featureFlags.vkBot) {
                di.direct.instance<VkBotPollingService>().start()
            }
            di.direct.instanceOrNull<HistoryMemoryWorker>()?.start(applicationScope)
        }
    }

    private val vkSessions = mutableMapOf<UUID, VkBotPollingService.PollSession>()

    suspend fun pollTelegramOnce() {
        for (binding in di.direct.instance<ru.souz.backend.telegram.TelegramBotBindingRepository>().listEnabled()) {
            di.direct.instance<TelegramBotPollingService>().pollBinding(binding.id)
        }
    }

    suspend fun pollVkOnce() {
        val bindings = di.direct.instance<PostgresVkBotBindingRepository>().listEnabled()
        vkSessions.keys.retainAll(bindings.map { it.id }.toSet())
        for (binding in bindings) {
            di.direct.instance<VkBotPollingService>().pollBinding(
                binding.id, vkSessions.getOrPut(binding.id) { VkBotPollingService.PollSession() },
            )
        }
    }

    suspend fun awaitExecution(id: UUID) { di.direct.instance<AgentExecutionLauncher>().join(id) }

    val jobService: JobService get() = di.direct.instance()

    val attachmentStorage: AttachmentStorage get() = di.direct.instance()
    val attachmentAccess: BackendAttachmentAccess get() = di.direct.instance()
    fun sandboxFileSystem(userId: String): SandboxFileSystem =
        di.direct.instance<ToolInvocationRuntimeSandboxResolver>().resolve(ToolInvocationMeta(userId)).fileSystem
    val messageRepository: MessageRepository get() = di.direct.instance()
    val agentStateRepository: AgentStateRepository get() = di.direct.instance()
    val turnRunner: BackendConversationTurnRunner get() = di.direct.instance()
    val conversationRuntimeFactory: BackendConversationRuntimeFactory get() = di.direct.instance()

    suspend fun startScheduledTaskWorker() {
        val runner = di.direct.instance<ScheduledTaskRunner>()
        di.direct.instance<PostgresJobService>().startWorker(applicationScope, runner::awaitCapacity, runner::run)
    }

    suspend fun runScheduledTask(job: JobRun) = di.direct.instance<ScheduledTaskRunner>().run(job)

    fun <T> sql(block: (Connection) -> T): T =
        dataSource.connection.use(block)

    val historyMemoryRepository: PostgresHistoryMemoryRepository get() = di.direct.instance()

    val toolCallRepository: ToolCallRepository get() = di.direct.instance()

    val executionRepository: ru.souz.backend.execution.repository.AgentExecutionRepository get() = di.direct.instance()

    val chatRepository: ru.souz.backend.chat.repository.ChatRepository get() = di.direct.instance()

    val optionRepository: OptionRepository get() = di.direct.instance()

    suspend fun recoverClientThreads() = di.direct.instance<ClientThreadRecoveryService>().recover()

    val applicationScope: BackendApplicationScope get() = di.direct.instance()

    val clientThreadRegistry: ClientThreadRuntimeRegistry get() = di.direct.instance()

    suspend fun shutdown() = resources.shutdown()

    suspend fun captureHistoryMemory(): Boolean = di.direct.instanceOrNull<HistoryMemoryWorker>()?.processNext() ?: false

    fun createPeer(llm: E2eLlmApi = E2eLlmApi(), providerClients: ProviderHttpClients? = null): BackendE2eBackend =
        BackendE2eBackend(
            schema = schema,
            featureFlags = featureFlags,
            skillOAuth = skillOAuth,
            llm = llm,
            telegramApi = null,
            vkApi = null,
            turnRunnerOverride = null,
            startBackgroundServices = false,
            providerClients = providerClients,
            settingsSource = settingsSource,
            localModels = localModels,
            hindsightUrl = hindsightUrl,
            clock = clock,
            hookConfig = hookConfig,
            scheduledTasks = scheduledTasks,
            llmLimits = llmLimits,
            sandboxFactory = sandboxFactory,
            attachments = attachments,
        )

    override fun close() {
        resources.close()
    }
}

internal fun clientToolsJson(vararg names: String): String = ru.souz.llms.restJsonMapper.writeValueAsString(
    names.map { ru.souz.backend.client.ClientToolDefinition(it, "Invoke $it", ru.souz.llms.LLMRequest.Parameters("object")) },
)
