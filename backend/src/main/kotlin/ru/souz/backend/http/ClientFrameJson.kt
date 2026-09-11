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
    var path = (this as? JsonMappingException)?.path.orEmpty().joinToString("") { reference ->
        val field = reference.fieldName
        value = if (field != null) value.path(field) else value.path(reference.index)
        "/" + (field ?: reference.index.toString()).replace("~", "~0").replace("/", "~1")
    }
    val details = clientFrameMapper.createObjectNode()
    val reason = when (this) {
        is InvalidTypeIdException -> {
            val discriminator = baseType.rawClass.getAnnotation(JsonTypeInfo::class.java)?.property
            if (discriminator != null) {
                path += "/$discriminator"
                value = value.path(discriminator)
            }
            baseType.rawClass.getAnnotation(JsonSubTypes::class.java)?.value?.let { subtypes ->
                details.putArray("expected").also { expected -> subtypes.forEach { expected.add(it.name) } }
            }
            // Only a bounded type discriminator is echoed; ordinary field values stay private.
            details.put("actual", typeId?.diagnosticIdentifier() ?: value.nodeType.name.lowercase())
            when {
                value.isMissingNode -> "missing_field"
                value.isNull -> "null_not_allowed"
                else -> "unknown_type"
            }
        }
        is UnrecognizedPropertyException -> "unknown_field"
        else -> {
            details.put("actual", value.nodeType.name.lowercase())
            when {
                value.isMissingNode -> "missing_field"
                value.isNull -> "null_not_allowed"
                this is MismatchedInputException -> {
                    targetType?.let { target ->
                        val expected = when {
                            target == String::class.java -> "string"
                            target == Boolean::class.java || target == Boolean::class.javaObjectType -> "boolean"
                            target.isPrimitive || Number::class.java.isAssignableFrom(target) -> "number"
                            target.isArray || Collection::class.java.isAssignableFrom(target) -> "array"
                            else -> "object"
                        }
                        details.putArray("expected").add(expected)
                    }
                    "type_mismatch"
                }
                else -> "invalid_value"
            }
        }
    }
    val safePath = path.diagnosticIdentifier()
    details.put("path", safePath).put("reason", reason)
    return ClientContractException(
        "invalid_request", "Invalid JSON field at ${safePath.ifEmpty { "<root>" }}: $reason.", details,
    )
}

internal class InvalidClientFrameException(message: String) : RuntimeException(message)

private fun String.diagnosticIdentifier(): String = take(128).replace(diagnosticControlCharacters, "_")

private val clientFrameMapper = jacksonObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
private val diagnosticControlCharacters = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")
