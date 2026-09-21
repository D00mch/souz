package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature
import com.fasterxml.jackson.module.kotlin.jacksonMapperBuilder
import ru.souz.backend.crypto.sha256Hex

internal object PublicPayloadHash {
    private val mapper = jacksonMapperBuilder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build()
    private val jsonWriter = mapper.writer().with(JsonNodeFeature.WRITE_PROPERTIES_SORTED)

    fun ofValue(value: Any): String = mapper.writeValueAsBytes(value).sha256Hex()

    fun ofJson(value: JsonNode): String = jsonWriter.writeValueAsBytes(value).sha256Hex()
}
