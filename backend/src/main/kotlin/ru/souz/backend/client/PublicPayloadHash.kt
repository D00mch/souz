package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest

internal object PublicPayloadHash {
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    fun ofValue(value: Any): String = digest(mapper.writeValueAsBytes(value))

    fun ofJson(value: JsonNode): String = digest(mapper.writeValueAsBytes(value.sorted()))

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun JsonNode.sorted(): JsonNode = when {
        isObject -> mapper.createObjectNode().also { output ->
            fields().asSequence().toList().sortedBy { it.key }.forEach { (key, value) ->
                output.set<JsonNode>(key, value.sorted())
            }
        }
        isArray -> mapper.createArrayNode().also { output ->
            forEach { output.add(it.sorted()) }
        }
        else -> this
    }
}
