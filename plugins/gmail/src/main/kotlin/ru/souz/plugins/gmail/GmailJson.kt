package ru.souz.plugins.gmail

import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.util.JsonRecyclerPools
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

internal val json = jacksonObjectMapper().apply {
    factory.setRecyclerPool(JsonRecyclerPools.nonRecyclingPool())
    factory.setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(36 * 1024 * 1024).build())
}
internal fun result(status: String): ObjectNode = json.createObjectNode().put("status", status)
internal fun tree(value: Any): ObjectNode = json.valueToTree(value)
internal fun JsonNode.text(name: String): String = path(name).takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
    ?: throw IllegalArgumentException("Missing or invalid $name")
internal fun messageId(value: String): String = value.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
internal fun historyId(value: String): String = value.also { require(it.matches(Regex("[0-9]{1,32}"))) }
