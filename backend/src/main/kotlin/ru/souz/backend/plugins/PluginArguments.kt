package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.JsonNode

/** JSON Pointers and schema expectations only; never include argument values. */
internal fun pluginArgumentErrors(value: JsonNode, schema: JsonNode): List<Map<String, Any>> = buildList {
    fun visit(value: JsonNode, schema: JsonNode, path: String) {
        if (size >= 32) return
        val type = schema.path("type").asText()
        val matches = when (type) {
            "object" -> value.isObject
            "array" -> value.isArray
            "string" -> value.isTextual
            "number" -> value.isNumber
            "integer" -> value.isIntegralNumber
            "boolean" -> value.isBoolean
            else -> false
        }
        if (!matches) {
            add(mapOf("path" to path, "reason" to "type_mismatch", "expected" to type))
            return
        }
        schema.get("enum")?.let {
            if (value !in it) add(mapOf("path" to path, "reason" to "enum_mismatch", "expected" to it))
        }
        if (value.isObject) {
            fun pointer(key: String) = "$path/${key.take(128).replace("~", "~0").replace("/", "~1")}".take(512)
            schema.path("required").forEach {
                if (!value.has(it.asText()) && size < 32) add(mapOf("path" to pointer(it.asText()), "reason" to "required"))
            }
            value.properties().forEach { (key, item) ->
                if (size < 32) {
                    val property = schema.path("properties").get(key)
                    if (property == null) add(mapOf("path" to pointer(key), "reason" to "unknown_field"))
                    else visit(item, property, pointer(key))
                }
            }
        }
        if (value.isArray) value.forEachIndexed { index, item -> visit(item, schema.path("items"), "$path/$index") }
    }
    visit(value, schema, "")
}
