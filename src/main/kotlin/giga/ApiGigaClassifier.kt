package com.dumch.giga

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory

class ApiGigaClassifier(
    private val api: GigaChatAPI,
) : GigaClassifier {
    private val l = LoggerFactory.getLogger(ApiGigaClassifier::class.java)
    private val logObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    override suspend fun classify(body: GigaRequest.Chat): GigaAgent.ToolCategory? {
        l.info("Classifying via API, body:\n{}", logObjectMapper.writeValueAsString(body))
        return when (val resp = api.message(body)) {
            is GigaResponse.Chat.Error -> {
                l.error("Classification error: {}", resp.message)
                null
            }
            is GigaResponse.Chat.Ok -> {
                val cat = resp.choices.firstOrNull()?.message?.content?.trim()?.uppercase()
                l.info("Category: {}", cat)
                try {
                    GigaAgent.ToolCategory.valueOf(cat ?: "desktop")
                } catch (e: IllegalArgumentException) {
                    l.error("Invalid category: {}", cat, e)
                    GigaAgent.ToolCategory.DESKTOP
                }
            }
        }
    }
}
