package ru.souz.llms

import ru.souz.tool.UserMessageClassifier
import ru.souz.tool.ToolCategory
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory

class ApiClassifier(
    private val api: LLMFactory,
) : UserMessageClassifier {
    private val l = LoggerFactory.getLogger(ApiClassifier::class.java)
    private val logObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val unknown = UserMessageClassifier.Reply(emptyList(), 0.0)
    private val noiceRegex = Regex("[<>'`“”«»\"]")
    private val spaceRegex = Regex("\\s+")

    override suspend fun classify(body: String): UserMessageClassifier.Reply {
        val req: LLMRequest.Chat = restJsonMapper.readValue(body)
        l.debug("Classifying via API, body:\n{}", logObjectMapper.writeValueAsString(req))
        return when (val resp = api.message(req)) {
            is LLMResponse.Chat.Error -> {
                l.error("Classification error: {}", resp.message)
                unknown
            }
            is LLMResponse.Chat.Ok -> {
                val rawContent = resp.choices.firstOrNull()?.message?.content?.trim()?.uppercase()
                    ?: return unknown
                val cleanedContent = rawContent.replace(noiceRegex, "").trim()

                // Parse format: CATEGORY1,CATEGORY2 CONFIDENCE
                // E.g. "MAIL,CALENDAR 90" or just "MAIL 90"
                val parts = cleanedContent.split(spaceRegex)
                if (parts.size < 2) return unknown

                val confidence = parts.last().toDoubleOrNull() ?: 0.0
                val categoriesStr = cleanedContent.substringBeforeLast(" ").trim()
                val categories = categoriesStr.split(",").mapNotNull {
                    try {
                        ToolCategory.valueOf(it.trim())
                    } catch (_: IllegalArgumentException) {
                        l.warn("Unknown category: {}", it)
                        null
                    }
                }

                l.info("Categories: {}, Confidence: {}", categories, confidence)
                UserMessageClassifier.Reply(categories, confidence)
            }
        }
    }
}