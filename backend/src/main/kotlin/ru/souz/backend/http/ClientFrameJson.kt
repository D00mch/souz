package ru.souz.backend.http

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import ru.souz.backend.client.ClientContractException

internal fun Frame.Text.parseClient(): JsonNode = parseClientFrame(readText())

internal suspend fun WebSocketSession.sendClient(value: Any) = send(Frame.Text(encodeClientFrame(value)))

internal fun <T> JsonNode.decodeClientFrame(type: Class<T>): T = try {
    clientFrameMapper.treeToValue(this, type)
} catch (error: JsonProcessingException) {
    throw error.toClientContractException(this)
} catch (_: IllegalArgumentException) {
    throw ClientContractException("invalid_request", "Frame does not match the public contract.")
}

private fun parseClientFrame(raw: String): JsonNode = try {
    clientFrameMapper.readTree(raw)?.also {
        if (!it.isObject) throw InvalidClientFrameException("Frame must be a JSON object.")
    } ?: throw InvalidClientFrameException("Frame must be valid JSON.")
} catch (_: JsonProcessingException) {
    throw InvalidClientFrameException("Frame must be valid JSON.")
}

private fun encodeClientFrame(value: Any): String = clientFrameMapper.writeValueAsString(value)

private fun JsonProcessingException.toClientContractException(frame: JsonNode): ClientContractException {
    var value = frame
    val path = StringBuilder()
    for (reference in (this as? JsonMappingException)?.path.orEmpty()) {
        val field = reference.fieldName
        value = if (field != null) value.path(field) else value.path(reference.index)
        path.append('/').append((field ?: reference.index.toString()).replace("~", "~0").replace("/", "~1"))
    }
    if (this is InvalidTypeIdException) {
        baseType.rawClass.getAnnotation(JsonTypeInfo::class.java)?.property?.let { discriminator ->
            path.append('/').append(discriminator)
            value = value.path(discriminator)
        }
    }
    val reason = when {
        this is UnrecognizedPropertyException -> "unknown_field"
        value.isMissingNode -> "missing_field"
        value.isNull -> "null_not_allowed"
        this is InvalidTypeIdException -> "unknown_type"
        this is MismatchedInputException -> "type_mismatch"
        else -> "invalid_value"
    }
    val details = clientFrameMapper.createObjectNode()
    if (this !is UnrecognizedPropertyException) {
        // Only a bounded type discriminator is echoed; ordinary field values stay private.
        val typeId = (this as? InvalidTypeIdException)?.typeId?.diagnosticIdentifier()
        details.put("actual", typeId ?: value.nodeType.name.lowercase())
    }
    when {
        this is InvalidTypeIdException -> baseType.rawClass.getAnnotation(JsonSubTypes::class.java)?.value?.let { subtypes ->
            details.putArray("expected").also { expected -> subtypes.forEach { expected.add(it.name) } }
        }
        this is MismatchedInputException && reason == "type_mismatch" -> targetType?.let { target ->
            details.putArray("expected").add(target.jsonTypeName())
        }
    }
    val safePath = path.toString().diagnosticIdentifier()
    details.put("path", safePath).put("reason", reason)
    return ClientContractException(
        "invalid_request", "Invalid JSON field at ${safePath.ifEmpty { "<root>" }}: $reason.", details,
    )
}

private fun Class<*>.jsonTypeName(): String = when {
    this == String::class.java -> "string"
    this == Boolean::class.java || this == Boolean::class.javaObjectType -> "boolean"
    isPrimitive || Number::class.java.isAssignableFrom(this) -> "number"
    isArray || Collection::class.java.isAssignableFrom(this) -> "array"
    else -> "object"
}

internal class InvalidClientFrameException(message: String) : RuntimeException(message)

private fun String.diagnosticIdentifier(): String = take(128).replace(diagnosticControlCharacters, "_")

private val clientFrameMapper = jacksonObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
private val diagnosticControlCharacters = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")
