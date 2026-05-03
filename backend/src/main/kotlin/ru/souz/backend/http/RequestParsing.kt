package ru.souz.backend.http

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import java.time.DateTimeException
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.llms.LLMModel

internal suspend inline fun <reified T : Any> ApplicationCall.receiveOrV1BadRequest(): T =
    receiveOrRequestError(::invalidV1Request)

internal fun BackendV1SettingsPatchRequest.toUserSettingsOverrides(): UserSettingsOverrides =
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

internal fun BackendV1OnboardingCompleteRequest.toUserSettingsOverrides(): UserSettingsOverrides =
    UserSettingsOverrides(
        defaultModel = defaultModel?.let { parseModel(it, fieldName = "defaultModel") },
        locale = locale?.let { parseLocale(it, fieldName = "locale") },
        timeZone = timeZone?.let { parseTimeZone(it, fieldName = "timeZone") },
        enabledTools = enabledTools?.map { toolName ->
            toolName.trim().takeIf { it.isNotEmpty() }
                ?: throw invalidV1Request("enabledTools must not contain blank values.")
        }?.toCollection(linkedSetOf()),
        showToolEvents = showToolEvents,
        streamingMessages = streamingMessages,
    )

internal fun BackendV1MessageOptionsRequest?.toUserSettingsOverrides(): UserSettingsOverrides =
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

internal fun parseModel(rawModel: String, fieldName: String): LLMModel =
    LLMModel.entries.firstOrNull { model ->
        model.alias.equals(rawModel.trim(), ignoreCase = true) || model.name.equals(rawModel.trim(), ignoreCase = true)
    } ?: throw invalidV1Request("$fieldName must be a known model alias.")

internal fun parseLocale(rawLocale: String, fieldName: String): Locale =
    Locale.forLanguageTag(rawLocale.trim())
        .takeIf { it.language.isNotBlank() }
        ?: throw invalidV1Request("$fieldName must be a valid locale.")

internal fun parseTimeZone(rawTimeZone: String, fieldName: String): ZoneId =
    try {
        ZoneId.of(rawTimeZone.trim())
    } catch (_: DateTimeException) {
        throw invalidV1Request("$fieldName must be a valid time zone.")
    }

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrRequestError(
    crossinline errorFactory: (String) -> RuntimeException,
): T =
    try {
        receive<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw errorFactory("Invalid payload: ${e.message ?: "request body cannot be parsed."}")
    }
