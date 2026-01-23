package ru.gigadesk.giga

import ru.gigadesk.tool.UserMessageClassifier
import ru.gigadesk.tool.ToolCategory
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory

class ApiClassifier(
    private val api: GigaChatAPI,
) : UserMessageClassifier {
    private val l = LoggerFactory.getLogger(ApiClassifier::class.java)
    private val logObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val unknown = UserMessageClassifier.Reply(null, emptyList(), 0.0)

    override suspend fun classify(body: String): UserMessageClassifier.Reply {
        val req: GigaRequest.Chat = gigaJsonMapper.readValue(body)
        l.debug("Classifying via API, body:\n{}", logObjectMapper.writeValueAsString(req))
        return when (val resp = api.message(req)) {
            is GigaResponse.Chat.Error -> {
                l.error("Classification error: {}", resp.message)
                unknown
            }
            is GigaResponse.Chat.Ok -> {
                val rawContent = resp.choices.firstOrNull()?.message?.content?.trim()?.uppercase()
                    ?: return unknown
                // Strip angle brackets and other noise from LLM response
                val cleanedContent = rawContent.replace(Regex("[<>]"), "").trim()
                
                // Parse format: CATEGORY1,CATEGORY2 CONFIDENCE
                // E.g. "MAIL,CALENDAR 90" or just "MAIL 90"
                val parts = cleanedContent.split(Regex("\\s+"))
                if (parts.size < 2) return unknown
                
                val confidence = parts.last().toDoubleOrNull() ?: 0.0
                val categoriesStr = cleanedContent.substringBeforeLast(" ").trim()
                val categories = categoriesStr.split(",").mapNotNull { 
                    try {
                        ToolCategory.valueOf(it.trim())
                    } catch (e: IllegalArgumentException) {
                        l.warn("Unknown category: {}", it)
                        null
                    }
                }
                
                val primary = categories.firstOrNull()
                l.info("Categories: {}, Confidence: {}", categories, confidence)

                UserMessageClassifier.Reply(primary, categories, confidence)
            }
        }
    }
}
