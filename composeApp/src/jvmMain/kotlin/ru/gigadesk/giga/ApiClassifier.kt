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

    private val unknown = UserMessageClassifier.Reply(null, 0.0)

    override suspend fun classify(body: String): UserMessageClassifier.Reply {
        val req: GigaRequest.Chat = gigaJsonMapper.readValue(body)
        l.debug("Classifying via API, body:\n{}", logObjectMapper.writeValueAsString(req))
        return when (val resp = api.message(req)) {
            is GigaResponse.Chat.Error -> {
                l.error("Classification error: {}", resp.message)
                unknown
            }
            is GigaResponse.Chat.Ok -> {
                val (cat, confidence) = resp.choices.firstOrNull()?.message?.content?.trim()?.uppercase()?.split(" ")
                    ?: return unknown
                l.info("Category: {}, {}", cat, confidence)
                UserMessageClassifier.Reply(ToolCategory.valueOf(cat), confidence.toDouble())
            }
        }
    }
}
