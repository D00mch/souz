package ru.souz.backend.http

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import java.net.InetSocketAddress
import java.time.DateTimeException
import java.time.ZoneId
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.backend.agent.model.AgentRequest
import ru.souz.backend.agent.service.BackendAgentService
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.chat.service.ChatService
import ru.souz.backend.chat.service.MessageService
import ru.souz.backend.choices.service.ChoiceService
import ru.souz.backend.common.BackendRequestException
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.security.RequestIdentityPlugin
import ru.souz.backend.security.requestIdentity
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.settings.service.UserSettingsService
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close

/** Health-check response returned by `GET /health`. */
data class HealthResponse(
    val status: String,
    val model: String,
)

/** Root endpoint response describing the available backend routes. */
data class RootResponse(
    val service: String,
    val endpoints: List<String>,
)

/** Minimal JSON error envelope used by backend routes. */
data class ErrorResponse(
    val error: String,
)

/** Embedded Ktor server wrapper for the Souz backend HTTP API. */
class BackendHttpServer(
    private val agentService: BackendAgentService,
    private val bootstrapService: BackendBootstrapService,
    private val userSettingsService: UserSettingsService? = null,
    private val providerKeyService: UserProviderKeyService? = null,
    private val chatService: ChatService? = null,
    private val messageService: MessageService? = null,
    private val executionService: AgentExecutionService? = null,
    private val choiceService: ChoiceService? = null,
    private val eventService: AgentEventService? = null,
    private val featureFlags: BackendFeatureFlags = BackendFeatureFlags(),
    private val selectedModel: () -> String,
    private val bindAddress: InetSocketAddress,
    private val internalAgentToken: () -> String? = { null },
    private val trustedProxyToken: () -> String? = { null },
) : AutoCloseable {
    private val l = LoggerFactory.getLogger(BackendHttpServer::class.java)
    private val server = embeddedServer(
        factory = Netty,
        host = bindAddress.hostString,
        port = bindAddress.port,
    ) {
        backendApplication(
            agentService = agentService,
            bootstrapService = bootstrapService,
            userSettingsService = userSettingsService,
            providerKeyService = providerKeyService,
            chatService = chatService,
            messageService = messageService,
            executionService = executionService,
            choiceService = choiceService,
            eventService = eventService,
            featureFlags = featureFlags,
            selectedModel = selectedModel,
            internalAgentToken = internalAgentToken,
            trustedProxyToken = trustedProxyToken,
        )
    }
    private var startedAddress: InetSocketAddress? = null

    val address: InetSocketAddress
        get() = startedAddress ?: bindAddress

    fun start() {
        server.start(wait = false)
        startedAddress = bindAddress
        l.info("Souz backend started on http://{}:{}", address.hostString, address.port)
    }

    override fun close() {
        server.stop(gracePeriodMillis = STOP_GRACE_PERIOD_MILLIS, timeoutMillis = STOP_TIMEOUT_MILLIS)
    }

    private companion object {
        const val STOP_GRACE_PERIOD_MILLIS = 500L
        const val STOP_TIMEOUT_MILLIS = 1_000L
    }
}

/** Installs backend HTTP routes into a Ktor application. */
fun Application.backendApplication(
    agentService: BackendAgentService,
    bootstrapService: BackendBootstrapService,
    userSettingsService: UserSettingsService? = null,
    providerKeyService: UserProviderKeyService? = null,
    chatService: ChatService? = null,
    messageService: MessageService? = null,
    executionService: AgentExecutionService? = null,
    choiceService: ChoiceService? = null,
    eventService: AgentEventService? = null,
    featureFlags: BackendFeatureFlags = BackendFeatureFlags(),
    selectedModel: () -> String,
    internalAgentToken: () -> String? = { null },
    trustedProxyToken: () -> String? = { null },
) {
    val l = LoggerFactory.getLogger("SouzBackendRoutes")

    install(ContentNegotiation) {
        jackson {
            registerKotlinModule()
            disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
    }
    install(WebSockets)
    install(StatusPages) {
        exception<BackendV1Exception> { call, cause ->
            call.respond(
                cause.status,
                BackendV1ErrorEnvelope(
                    error = BackendV1Error(code = cause.code, message = cause.message),
                ),
            )
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) {
                throw cause
            }
            l.error("Unhandled backend request failure", cause)
            if (call.request.path().startsWith("/v1/")) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    BackendV1ErrorEnvelope(
                        error = BackendV1Error(
                            code = "internal_error",
                            message = "Internal server error.",
                        ),
                    ),
                )
            } else {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error."))
            }
        }
    }
    install(RequestIdentityPlugin) {
        this.trustedProxyToken = trustedProxyToken
    }

    routing {
        get("/") {
            call.respondBackend(l) {
                RootResponse(
                    service = "souz-backend",
                    endpoints = listOf(
                        "GET /health",
                        "GET /v1/bootstrap",
                        "GET /v1/me/settings",
                        "PATCH /v1/me/settings",
                        "GET /v1/me/provider-keys",
                        "PUT /v1/me/provider-keys/{provider}",
                        "DELETE /v1/me/provider-keys/{provider}",
                        "GET /v1/chats",
                        "POST /v1/chats",
                        "GET /v1/chats/{chatId}/messages",
                        "GET /v1/chats/{chatId}/events",
                        "POST /v1/chats/{chatId}/messages",
                        "POST /v1/chats/{chatId}/cancel-active",
                        "POST /v1/chats/{chatId}/executions/{executionId}/cancel",
                        "POST /v1/choices/{choiceId}/answer",
                        "WS /v1/chats/{chatId}/ws",
                        "POST /agent",
                    ),
                )
            }
        }

        get("/health") {
            call.respondBackend(l) {
                HealthResponse(status = "ok", model = selectedModel())
            }
        }

        route("/v1") {
            get("/bootstrap") {
                call.respond(
                    bootstrapService.response(call.requestIdentity())
                )
            }

            route("/me") {
                get("/settings") {
                    val service = requireV1Service(userSettingsService, "User settings")
                    call.respond(
                        BackendV1SettingsResponse(
                            settings = service.get(call.requestIdentity().userId).toDto(),
                        )
                    )
                }

                patch("/settings") {
                    val service = requireV1Service(userSettingsService, "User settings")
                    call.requireJsonContentV1()
                    val request = call.receiveOrV1BadRequest<BackendV1SettingsPatchRequest>()
                    call.respond(
                        BackendV1SettingsResponse(
                            settings = service.patch(
                                userId = call.requestIdentity().userId,
                                overrides = request.toUserSettingsOverrides(),
                            ).toDto(),
                        )
                    )
                }

                get("/provider-keys") {
                    val service = requireV1Service(providerKeyService, "Provider keys")
                    call.respond(
                        BackendV1ProviderKeysResponse(
                            items = service.list(call.requestIdentity().userId).map { it.toDto() },
                        )
                    )
                }

                put("/provider-keys/{provider}") {
                    val service = requireV1Service(providerKeyService, "Provider keys")
                    call.requireJsonContentV1()
                    val request = call.receiveOrV1BadRequest<BackendV1PutProviderKeyRequest>()
                    val apiKey = request.apiKey.trim().takeIf { it.isNotEmpty() }
                        ?: throw invalidV1Request("apiKey must not be empty.")
                    call.respond(
                        BackendV1PutProviderKeyResponse(
                            providerKey = service.put(
                                userId = call.requestIdentity().userId,
                                provider = call.requireProvider(),
                                apiKey = apiKey,
                            ).toDto()
                        )
                    )
                }

                delete("/provider-keys/{provider}") {
                    val service = requireV1Service(providerKeyService, "Provider keys")
                    service.delete(
                        userId = call.requestIdentity().userId,
                        provider = call.requireProvider(),
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/chats") {
                get {
                    val service = requireV1Service(chatService, "Chat")
                    val limit = call.queryPositiveInt("limit", DEFAULT_CHAT_LIMIT)
                    val includeArchived = call.queryBoolean("includeArchived", defaultValue = false)
                    call.respond(
                        service.list(
                            userId = call.requestIdentity().userId,
                            limit = limit,
                            includeArchived = includeArchived,
                        ).let { page ->
                            BackendV1ChatsResponse(
                                items = page.items.map { it.toDto() },
                                nextCursor = page.nextCursor,
                            )
                        }
                    )
                }

                post {
                    val service = requireV1Service(chatService, "Chat")
                    call.requireJsonContentV1()
                    val request = call.receiveOrV1BadRequest<BackendV1CreateChatRequest>()
                    call.respond(
                        HttpStatusCode.Created,
                        BackendV1CreateChatResponse(
                            chat = service.create(
                                userId = call.requestIdentity().userId,
                                title = request.title,
                            ).toDto(),
                        ),
                    )
                }

                route("/{chatId}/messages") {
                    get {
                        val service = requireV1Service(messageService, "Message")
                        val chatId = call.requireChatId()
                        val limit = call.queryPositiveInt("limit", DEFAULT_MESSAGE_LIMIT)
                        call.respond(
                            service.list(
                                userId = call.requestIdentity().userId,
                                chatId = chatId,
                                beforeSeq = call.queryPositiveLong("beforeSeq"),
                                afterSeq = call.queryPositiveLong("afterSeq"),
                                limit = limit,
                            ).let { page ->
                                BackendV1MessagesResponse(
                                    items = page.items.map { it.toDto() },
                                    nextBeforeSeq = page.nextBeforeSeq,
                                )
                            }
                        )
                    }

                    post {
                        val service = requireV1Service(messageService, "Message")
                        val chatId = call.requireChatId()
                        call.requireJsonContentV1()
                        val request = call.receiveOrV1BadRequest<BackendV1CreateMessageRequest>()
                        val content = request.content.trim().takeIf { it.isNotEmpty() }
                            ?: throw invalidV1Request("content must not be empty.")
                        call.respond(
                            service.send(
                                userId = call.requestIdentity().userId,
                                chatId = chatId,
                                content = content,
                                clientMessageId = request.clientMessageId,
                                requestOverrides = request.options.toUserSettingsOverrides(),
                            ).toResponse()
                        )
                    }
                }

                get("/{chatId}/events") {
                    requireWsEventsEnabled(featureFlags)
                    val service = requireV1Service(eventService, "Event")
                    call.respond(
                        BackendV1EventsResponse(
                            items = service.listByChat(
                                userId = call.requestIdentity().userId,
                                chatId = call.requireChatId(),
                                afterSeq = call.queryNonNegativeLong("afterSeq"),
                            ).map { it.toDto() },
                        )
                    )
                }

                get("/{chatId}/ws") {
                    requireWsEventsEnabled(featureFlags)
                    throw invalidV1Request("WebSocket upgrade is required.")
                }

                webSocket("/{chatId}/ws") {
                    if (!featureFlags.wsEvents) {
                        close(
                            CloseReason(
                                CloseReason.Codes.TRY_AGAIN_LATER,
                                "WebSocket events feature is disabled.",
                            )
                        )
                        return@webSocket
                    }
                    val service = requireV1Service(eventService, "Event")
                    val chatId = call.requireChatId()
                    val afterSeq = call.queryNonNegativeLong("afterSeq")
                    val stream = try {
                        service.openStream(
                            userId = call.requestIdentity().userId,
                            chatId = chatId,
                            afterSeq = afterSeq,
                        )
                    } catch (e: BackendV1Exception) {
                        close(
                            CloseReason(
                                CloseReason.Codes.VIOLATED_POLICY,
                                e.message,
                            )
                        )
                        return@webSocket
                    }

                    var lastSeq = afterSeq ?: 0L
                    try {
                        stream.replay.forEach { event ->
                            if (event.seq > lastSeq) {
                                send(Frame.Text(websocketEventMapper.writeValueAsString(event.toDto())))
                                lastSeq = event.seq
                            }
                        }
                        for (event in stream.liveEvents) {
                            if (event.seq > lastSeq) {
                                send(Frame.Text(websocketEventMapper.writeValueAsString(event.toDto())))
                                lastSeq = event.seq
                            }
                        }
                    } finally {
                        stream.close()
                    }
                }

                post("/{chatId}/cancel-active") {
                    val service = requireV1Service(executionService, "Execution")
                    call.respond(
                        service.cancelActive(
                            userId = call.requestIdentity().userId,
                            chatId = call.requireChatId(),
                        ).toResponse()
                    )
                }

                post("/{chatId}/executions/{executionId}/cancel") {
                    val service = requireV1Service(executionService, "Execution")
                    call.respond(
                        service.cancelExecution(
                            userId = call.requestIdentity().userId,
                            chatId = call.requireChatId(),
                            executionId = call.requireExecutionId(),
                        ).toResponse()
                    )
                }
            }

            route("/choices") {
                post("/{choiceId}/answer") {
                    val service = requireV1Service(choiceService, "Choice")
                    call.requireJsonContentV1()
                    val request = call.receiveOrV1BadRequest<BackendV1AnswerChoiceRequest>()
                    call.respond(
                        service.answer(
                            userId = call.requestIdentity().userId,
                            choiceId = call.requireChoiceId(),
                            selectedOptionIds = request.selectedOptionIds,
                            freeText = request.freeText,
                            metadata = request.metadata,
                        ).toResponse()
                    )
                }
            }
        }

        post("/agent") {
            call.respondBackend(l) {
                requireAgentAuthorization(internalAgentToken())
                requireJsonContent()
                val request = receiveOrBadRequest<AgentRequest>()
                requireMatchingRequestId(request.requestId)
                agentService.sendAgentRequest(request)
            }
        }
    }
}

private suspend fun ApplicationCall.respondBackend(
    logger: org.slf4j.Logger,
    block: suspend ApplicationCall.() -> Any,
) {
    try {
        respond(block())
    } catch (e: BackendRequestException) {
        respond(HttpStatusCode.fromValue(e.statusCode), ErrorResponse(e.message))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Unhandled backend request failure", e)
        respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error."))
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrBadRequest(): T =
    try {
        receive<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BackendRequestException(400, "Invalid payload: ${e.message ?: "request body cannot be parsed."}")
    }

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrV1BadRequest(): T =
    try {
        receive<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw invalidV1Request("Invalid payload: ${e.message ?: "request body cannot be parsed."}")
    }

private fun ApplicationCall.requireAgentAuthorization(expectedToken: String?) {
    val token = expectedToken?.trim().takeUnless { it.isNullOrEmpty() }
        ?: throw BackendRequestException(401, "Missing or invalid internal token.")
    val authorization = request.header(HttpHeaders.Authorization)?.trim().orEmpty()
    val actualToken = authorization.removePrefix(BEARER_PREFIX).takeIf {
        authorization.startsWith(BEARER_PREFIX)
    }?.trim()
    if (actualToken != token) {
        throw BackendRequestException(401, "Missing or invalid internal token.")
    }
}

private fun ApplicationCall.requireJsonContent() {
    val contentType = request.contentType()
    if (
        contentType.contentType != ContentType.Application.Json.contentType ||
        contentType.contentSubtype != ContentType.Application.Json.contentSubtype
    ) {
        throw BackendRequestException(400, "Content-Type must be application/json.")
    }
}

private fun ApplicationCall.requireJsonContentV1() {
    val contentType = request.contentType()
    if (
        contentType.contentType != ContentType.Application.Json.contentType ||
        contentType.contentSubtype != ContentType.Application.Json.contentSubtype
    ) {
        throw invalidV1Request("Content-Type must be application/json.")
    }
}

private fun ApplicationCall.requireMatchingRequestId(bodyRequestId: String) {
    val headerRequestId = request.header(REQUEST_ID_HEADER)?.trim()
    val headerUuid = headerRequestId?.toUuidOrNull()
    val bodyUuid = bodyRequestId.toUuidOrNull()
    if (headerUuid == null) {
        throw BackendRequestException(400, "$REQUEST_ID_HEADER must be a UUID.")
    }
    if (bodyUuid == null || headerUuid != bodyUuid) {
        throw BackendRequestException(400, "$REQUEST_ID_HEADER must match requestId.")
    }
}

private fun String.toUuidOrNull(): UUID? =
    runCatching { UUID.fromString(this) }.getOrNull()

private fun BackendV1SettingsPatchRequest.toUserSettingsOverrides(): UserSettingsOverrides =
    UserSettingsOverrides(
        defaultModel = defaultModel?.let { parseModel(it, fieldName = "defaultModel") },
        contextSize = contextSize?.takeIf { it > 0 }
            ?: contextSize?.let { throw invalidV1Request("contextSize must be positive.") },
        temperature = temperature?.takeIf { it.isFinite() }
            ?: temperature?.let { throw invalidV1Request("temperature must be finite.") },
        locale = locale?.let { parseLocale(it, fieldName = "locale") },
        timeZone = timeZone?.let { parseTimeZone(it, fieldName = "timeZone") },
        systemPrompt = systemPrompt?.trim()?.takeIf { it.isNotEmpty() },
        enabledTools = enabledTools?.map { toolName ->
            toolName.trim().takeIf { it.isNotEmpty() }
                ?: throw invalidV1Request("enabledTools must not contain blank values.")
        }?.toCollection(linkedSetOf()),
        showToolEvents = showToolEvents,
        streamingMessages = streamingMessages,
    )

private fun BackendV1MessageOptionsRequest?.toUserSettingsOverrides(): UserSettingsOverrides =
    UserSettingsOverrides(
        defaultModel = this?.model?.let { parseModel(it, fieldName = "options.model") },
        contextSize = this?.contextSize?.takeIf { it > 0 }
            ?: this?.contextSize?.let { throw invalidV1Request("options.contextSize must be positive.") },
        temperature = this?.temperature?.takeIf { it.isFinite() }
            ?: this?.temperature?.let { throw invalidV1Request("options.temperature must be finite.") },
        locale = this?.locale?.let { parseLocale(it, fieldName = "options.locale") },
        timeZone = this?.timeZone?.let { parseTimeZone(it, fieldName = "options.timeZone") },
        systemPrompt = this?.systemPrompt?.trim()?.takeIf { it.isNotEmpty() },
    )

private fun parseModel(rawModel: String, fieldName: String): LLMModel =
    LLMModel.entries.firstOrNull { model ->
        model.alias.equals(rawModel.trim(), ignoreCase = true) || model.name.equals(rawModel.trim(), ignoreCase = true)
    } ?: throw invalidV1Request("$fieldName must be a known model alias.")

private fun parseLocale(rawLocale: String, fieldName: String): Locale =
    Locale.forLanguageTag(rawLocale.trim())
        .takeIf { it.language.isNotBlank() }
        ?: throw invalidV1Request("$fieldName must be a valid locale.")

private fun parseTimeZone(rawTimeZone: String, fieldName: String): ZoneId =
    try {
        ZoneId.of(rawTimeZone.trim())
    } catch (_: DateTimeException) {
        throw invalidV1Request("$fieldName must be a valid time zone.")
    }

private fun ApplicationCall.requireChatId(): UUID =
    parameters["chatId"]?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { value ->
            runCatching { UUID.fromString(value) }.getOrElse {
                throw invalidV1Request("chatId must be a UUID.")
            }
        }
        ?: throw invalidV1Request("chatId must be a UUID.")

private fun ApplicationCall.requireProvider(): LlmProvider {
    val raw = parameters["provider"]?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw invalidV1Request("provider path parameter is required.")
    val normalized = raw.replace('-', '_')
    return LlmProvider.entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        ?: throw invalidV1Request("Unsupported provider '$raw'.")
}

private fun ApplicationCall.requireExecutionId(): UUID =
    parameters["executionId"]?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { value ->
            runCatching { UUID.fromString(value) }.getOrElse {
                throw invalidV1Request("executionId must be a UUID.")
            }
        }
        ?: throw invalidV1Request("executionId must be a UUID.")

private fun ApplicationCall.requireChoiceId(): UUID =
    parameters["choiceId"]?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { value ->
            runCatching { UUID.fromString(value) }.getOrElse {
                throw invalidV1Request("choiceId must be a UUID.")
            }
        }
        ?: throw invalidV1Request("choiceId must be a UUID.")

private fun ApplicationCall.queryPositiveInt(name: String, defaultValue: Int): Int {
    val rawValue = request.queryParameters[name] ?: return defaultValue
    return rawValue.toIntOrNull()?.takeIf { it > 0 }
        ?: throw invalidV1Request("$name must be positive.")
}

private fun ApplicationCall.queryPositiveLong(name: String): Long? {
    val rawValue = request.queryParameters[name] ?: return null
    return rawValue.toLongOrNull()?.takeIf { it > 0L }
        ?: throw invalidV1Request("$name must be positive.")
}

private fun ApplicationCall.queryNonNegativeLong(name: String): Long? {
    val rawValue = request.queryParameters[name] ?: return null
    return rawValue.toLongOrNull()?.takeIf { it >= 0L }
        ?: throw invalidV1Request("$name must be non-negative.")
}

private fun ApplicationCall.queryBoolean(name: String, defaultValue: Boolean): Boolean {
    val rawValue = request.queryParameters[name] ?: return defaultValue
    return rawValue.toBooleanStrictOrNull()
        ?: throw invalidV1Request("$name must be true or false.")
}

private fun <T> requireV1Service(service: T?, name: String): T =
    service ?: throw BackendV1Exception(
        status = HttpStatusCode.InternalServerError,
        code = "internal_error",
        message = "$name service is unavailable.",
    )

private fun requireWsEventsEnabled(featureFlags: BackendFeatureFlags) {
    if (!featureFlags.wsEvents) {
        throw featureDisabledV1("WebSocket events feature is disabled.")
    }
}

private const val BEARER_PREFIX = "Bearer "
private const val REQUEST_ID_HEADER = "X-Request-Id"
private const val DEFAULT_CHAT_LIMIT = 50
private const val DEFAULT_MESSAGE_LIMIT = 100
private val websocketEventMapper = jacksonObjectMapper().registerKotlinModule()
