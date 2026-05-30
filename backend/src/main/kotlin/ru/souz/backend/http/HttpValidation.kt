package ru.souz.backend.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import java.util.UUID
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.common.BackendRequestException
import ru.souz.backend.common.normalizePositiveLimit
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.bus.AgentEventLimits
import ru.souz.backend.security.requestIdentity
import ru.souz.llms.LlmProvider

internal fun ApplicationCall.requireJsonContent() {
    requireJsonContent { message -> BackendRequestException(400, message) }
}

internal fun ApplicationCall.requireJsonContentV1() {
    requireJsonContent(::invalidV1Request)
}

internal fun ApplicationCall.requireUserIdFromTrustedProxy(): String = requestIdentity().userId

internal fun ApplicationCall.requireChatId(): UUID = requireUuidParameter("chatId", "chatId must be a UUID.")

internal fun ApplicationCall.requireProvider(): LlmProvider {
    val raw = parameters["provider"]?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw invalidV1Request("provider path parameter is required.")
    val normalized = raw.replace('-', '_')
    return LlmProvider.entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        ?: throw invalidV1Request("Unsupported provider '$raw'.")
}

internal fun ApplicationCall.requireExecutionId(): UUID =
    requireUuidParameter("executionId", "executionId must be a UUID.")

internal fun ApplicationCall.requireOptionId(): UUID =
    requireUuidParameter("optionId", "optionId must be a UUID.")

internal fun ApplicationCall.queryPositiveInt(name: String, defaultValue: Int, max: Int): Int {
    val rawValue = request.queryParameters[name] ?: return normalizePositiveLimit(defaultValue, max)
    return rawValue.toIntOrNull()
        ?.let { value ->
            if (value <= 0) {
                throw invalidV1Request("$name must be positive.")
            }
            normalizePositiveLimit(value, max)
        }
        ?: throw invalidV1Request("$name must be positive.")
}

internal fun ApplicationCall.queryPositiveLong(name: String): Long? {
    val rawValue = request.queryParameters[name] ?: return null
    return rawValue.toLongOrNull()?.takeIf { it > 0L }
        ?: throw invalidV1Request("$name must be positive.")
}

internal fun ApplicationCall.queryNonNegativeLong(name: String): Long? {
    val rawValue = request.queryParameters[name] ?: return null
    return rawValue.toLongOrNull()?.takeIf { it >= 0L }
        ?: throw invalidV1Request("$name must be non-negative.")
}

internal fun ApplicationCall.queryBoolean(name: String, defaultValue: Boolean): Boolean {
    val rawValue = request.queryParameters[name] ?: return defaultValue
    return rawValue.toBooleanStrictOrNull()
        ?: throw invalidV1Request("$name must be true or false.")
}

internal fun <T> requireV1Service(service: T?, name: String): T =
    service ?: throw BackendV1Exception(
        status = HttpStatusCode.InternalServerError,
        code = "internal_error",
        message = "$name service is unavailable.",
    )

internal fun requireWsEventsEnabled(featureFlags: BackendFeatureFlags) {
    if (!featureFlags.wsEvents) {
        throw featureDisabledV1("WebSocket events feature is disabled.")
    }
}

internal const val DEFAULT_CHAT_LIMIT = ChatRepository.DEFAULT_LIMIT
internal const val MAX_CHAT_LIMIT = ChatRepository.MAX_LIMIT
internal const val DEFAULT_MESSAGE_LIMIT = MessageRepository.DEFAULT_LIMIT
internal const val MAX_MESSAGE_LIMIT = MessageRepository.MAX_LIMIT
internal const val DEFAULT_EVENT_LIMIT = AgentEventLimits.DEFAULT_REPLAY_LIMIT
internal const val MAX_EVENT_LIMIT = AgentEventLimits.MAX_REPLAY_LIMIT

private fun ApplicationCall.requireJsonContent(errorFactory: (String) -> RuntimeException) {
    val contentType = request.contentType()
    if (
        contentType.contentType != ContentType.Application.Json.contentType ||
        contentType.contentSubtype != ContentType.Application.Json.contentSubtype
    ) {
        throw errorFactory("Content-Type must be application/json.")
    }
}

private fun ApplicationCall.requireUuidParameter(name: String, errorMessage: String): UUID =
    parameters[name]?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { value ->
            runCatching { UUID.fromString(value) }.getOrElse {
                throw invalidV1Request(errorMessage)
            }
        }
        ?: throw invalidV1Request(errorMessage)
