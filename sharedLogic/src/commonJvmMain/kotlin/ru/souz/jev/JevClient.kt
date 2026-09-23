package ru.souz.jev

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import ru.souz.llms.restJsonMapper

/** Reusable Noul evaluation; the caller owns the supplied HTTP client. */
class JevClient(
    private val client: HttpClient,
    private val token: String = System.getenv("JEV_TOKEN").orEmpty().trim(),
    private val model: String = System.getenv("JEV_MODEL")?.trim()?.takeIf(String::isNotEmpty) ?: "jev-latest",
) {
    init {
        require(token.isNotBlank()) { "JEV_TOKEN is required when using Jev" }
        require(model.isNotBlank()) { "Jev model must not be blank" }
    }

    suspend fun evaluate(
        state: JsonNode,
        questions: Map<String, String>,
    ): Map<String, Double> {
        if (questions.isEmpty()) return emptyMap()
        require(state.isTextual || state.isObject || state.isArray) { "Jev state must be text, an object, or an array" }
        val response = client.post("https://api.typesafe.ai/v1/systemone") {
            expectSuccess = false
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 30_000 }
            setBody(mapOf("state" to state, "model" to model, "questions" to questions.mapValues { (_, instructions) ->
                mapOf("type" to "noul", "instructions" to instructions)
            }))
        }
        check(response.status.isSuccess()) { "Jev request failed (HTTP ${response.status.value})" }
        val result = try {
            restJsonMapper.readTree(response.bodyAsText())
        } catch (_: JsonProcessingException) {
            error("Jev returned invalid JSON")
        }
        check(result != null) { "Jev returned an empty response" }
        return questions.mapValues { (name, _) ->
            val answer = result.path("answers").path(name)
            val probability = answer.path("noul")
            val value = probability.asDouble()
            check(answer.path("type").asText() == "noul" && probability.isNumber &&
                value in 0.0..1.0) {
                "Jev response contains a missing or invalid Noul answer"
            }
            value
        }
    }
}
