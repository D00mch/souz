package com.dumch.giga

import com.dumch.tool.GigaClassifier
import com.dumch.tool.ToolCategory
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory

class ApiGigaClassifier(
    private val api: GigaChatAPI,
) : GigaClassifier {
    private val l = LoggerFactory.getLogger(ApiGigaClassifier::class.java)
    private val logObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    override suspend fun classify(body: String): ToolCategory? {
        val req: GigaRequest.Chat = try {
            gigaJsonMapper.readValue(body)
        } catch (_: Exception) {
            return null
        }
        l.info("Classifying via API, body:\n{}", logObjectMapper.writeValueAsString(req))
        return when (val resp = api.message(req)) {
            is GigaResponse.Chat.Error -> {
                l.error("Classification error: {}", resp.message)
                null
            }
            is GigaResponse.Chat.Ok -> {
                val cat = resp.choices.firstOrNull()?.message?.content?.trim()?.uppercase()
                l.info("Category: {}", cat)
                try {
                    ToolCategory.valueOf(cat ?: "DESKTOP")
                } catch (e: IllegalArgumentException) {
                    l.error("Invalid category: {}", cat, e)
                    ToolCategory.DESKTOP
                }
            }
        }
    }
}
