package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.sql.Connection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.app.BackendAppConfig
import ru.souz.backend.app.BackendApplicationScope
import ru.souz.backend.app.BackendRuntimeResources
import ru.souz.backend.app.backendDiModule
import ru.souz.backend.client.ClientThreadRecoveryService
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendOpenApiSecurity
import ru.souz.backend.http.backendApplication
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramBotPollingService
import ru.souz.llms.LLMModel
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalProviderAvailability
import kotlin.time.Duration.Companion.milliseconds

internal const val E2E_PROXY_TOKEN = "proxy-secret"
internal const val E2E_TELEGRAM_TOKEN_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
internal val E2E_LOCAL_MODEL: LLMModel = LLMModel.LocalQwen3_4B_Instruct_2507

internal fun backendE2eTest(
    schemaPrefix: String,
    schema: String = newPostgresSchema(schemaPrefix),
    featureFlags: BackendFeatureFlags = BackendFeatureFlags(wsEvents = true),
    llm: E2eLlmApi = E2eLlmApi(),
    telegramApi: TelegramBotApi? = null,
    turnRunnerOverride: BackendConversationTurnRunner? = null,
    startBackgroundServices: Boolean = false,
    block: suspend BackendE2eScope.() -> Unit,
) = testApplication {
    val backend = BackendE2eBackend(
        schema = schema,
        featureFlags = featureFlags,
        llm = llm,
        telegramApi = telegramApi,
        turnRunnerOverride = turnRunnerOverride,
        startBackgroundServices = startBackgroundServices,
    )
    application {
        backendApplication(backend.dependencies)
    }
    backend.use { backend ->
        BackendE2eScope(this, backend, llm).block()
    }
}

internal class BackendE2eScope(
    private val app: ApplicationTestBuilder,
    val backend: BackendE2eBackend,
    val llm: E2eLlmApi,
) {
    val client: HttpClient get() = app.client
    val json = jacksonObjectMapper()

    fun webSocketClient(): HttpClient = app.createClient {
        install(WebSockets)
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
}

internal class BackendE2eBackend(
    schema: String,
    featureFlags: BackendFeatureFlags,
    llm: E2eLlmApi,
    telegramApi: TelegramBotApi?,
    turnRunnerOverride: BackendConversationTurnRunner?,
    startBackgroundServices: Boolean,
) : AutoCloseable {
    private val appConfig: BackendAppConfig = postgresAppConfig(
        schema = schema,
        featureFlags = featureFlags,
        proxyToken = E2E_PROXY_TOKEN,
        telegramTokenEncryptionKey = E2E_TELEGRAM_TOKEN_KEY.takeIf { featureFlags.telegramBot },
        includeSkillOAuthConfig = false,
    )
    private val localChatApi = localChatApiBackedBy(llm)
    private val localAvailability = localProviderAvailability()
    private val localRuntime = relaxedLocalRuntime()

    private val di = DI.invoke(allowSilentOverride = true) {
        import(
            backendDiModule(
                systemPrompt = "You are the backend E2E assistant.",
                appConfig = appConfig,
            )
        )
        bindSingleton<LocalProviderAvailability>(overrides = true) { localAvailability }
        bindSingleton<LocalLlamaRuntime>(overrides = true) { localRuntime }
        bindSingleton<LocalChatAPI>(overrides = true) { localChatApi }
        if (telegramApi != null) {
            bindSingleton<TelegramBotApi>(overrides = true) { telegramApi }
        }
        if (turnRunnerOverride != null) {
            bindSingleton<BackendConversationTurnRunner>(overrides = true) { turnRunnerOverride }
        }
    }

    val dependencies: BackendHttpDependencies = di.direct.instance()
    private val dataSource: HikariDataSource = di.direct.instance()
    private val resources: BackendRuntimeResources = di.direct.instance()

    init {
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
        }
    }

    fun <T> sql(block: (Connection) -> T): T =
        dataSource.connection.use(block)

    override fun close() {
        resources.close()
    }
}
