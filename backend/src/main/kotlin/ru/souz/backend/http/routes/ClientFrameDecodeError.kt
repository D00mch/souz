package ru.souz.backend.http.routes

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import ru.souz.backend.client.ClientContractException

internal fun clientFrameDecodeError(node: JsonNode, error: JsonProcessingException): ClientContractException {
    var value = node
    var path = (error as? JsonMappingException)?.path.orEmpty().joinToString("") { reference ->
        val field = reference.fieldName
        value = if (field != null) value.path(field) else value.path(reference.index)
        "/" + (field ?: reference.index.toString()).replace("~", "~0").replace("/", "~1")
    }
    val details = JsonNodeFactory.instance.objectNode()
    val reason = when (error) {
        is InvalidTypeIdException -> {
            val discriminator = error.baseType.rawClass.getAnnotation(JsonTypeInfo::class.java)?.property
            if (discriminator != null) {
                path += "/$discriminator"
                value = value.path(discriminator)
            }
            error.baseType.rawClass.getAnnotation(JsonSubTypes::class.java)?.value?.let { subtypes ->
                details.putArray("expected").also { expected -> subtypes.forEach { expected.add(it.name) } }
            }
            // Only a bounded type discriminator is echoed; ordinary field values stay private.
            details.put("actual", error.typeId?.diagnosticIdentifier() ?: value.nodeType.name.lowercase())
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
                error is MismatchedInputException -> {
                    error.targetType?.let { target ->
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

private fun String.diagnosticIdentifier(): String = take(128).replace(diagnosticControlCharacters, "_")

private val diagnosticControlCharacters = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")
