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

data class JevNoulQuestion(val instructions: String) {
    val type: String = "noul"
}

data class JevUsage(val inputTokens: Long, val outputTokens: Long)

data class JevResult(
    val model: String,
    val probabilities: Map<String, Double>,
    val usage: JevUsage,
)

/** Reusable Noul evaluation; the caller owns the supplied HTTP client. */
class JevClient(
    private val client: HttpClient,
    private val tokenProvider: () -> String? = { System.getenv("JEV_TOKEN") },
    private val modelProvider: () -> String? = { System.getenv("JEV_MODEL") },
) {
    fun requireConfigured() {
        token()
    }

    suspend fun evaluate(
        state: JsonNode,
        questions: Map<String, JevNoulQuestion>,
        model: String = modelProvider()?.trim()?.takeIf(String::isNotEmpty) ?: "jev-latest",
    ): JevResult {
        require(state.isTextual || state.isObject || state.isArray) { "Jev state must be text, an object, or an array" }
        require(questions.isNotEmpty()) { "Jev requires at least one question" }
        require(model.isNotBlank()) { "Jev model must not be blank" }
        val response = client.post("https://api.typesafe.ai/v1/systemone") {
            expectSuccess = false
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 30_000 }
            setBody(mapOf("state" to state, "model" to model, "questions" to questions))
        }
        check(response.status.isSuccess()) { "Jev request failed (HTTP ${response.status.value})" }
        val result = try {
            restJsonMapper.readTree(response.bodyAsText())
        } catch (_: JsonProcessingException) {
            error("Jev returned invalid JSON")
        }
        check(result != null) { "Jev returned an empty response" }
        val resolvedModel = result.path("model")
        check(resolvedModel.isTextual && resolvedModel.asText().isNotBlank()) { "Jev response is missing its model" }
        val probabilities = questions.mapValues { (name, _) ->
            val answer = result.path("answers").path(name)
            val probability = answer.path("noul")
            check(answer.path("type").asText() == "noul" && probability.isNumber &&
                probability.asDouble().isFinite() && probability.asDouble() in 0.0..1.0) {
                "Jev response contains a missing or invalid Noul answer"
            }
            probability.asDouble()
        }
        return JevResult(
            model = resolvedModel.asText(),
            probabilities = probabilities,
            usage = JevUsage(
                inputTokens = result.path("usage").tokenCount("input_tokens"),
                outputTokens = result.path("usage").tokenCount("output_tokens"),
            ),
        )
    }

    private fun token(): String = tokenProvider()?.trim()?.takeIf(String::isNotEmpty)
        ?: error("JEV_TOKEN is required when using Jev")

    private fun JsonNode.tokenCount(name: String): Long {
        val count = path(name)
        check(count.isIntegralNumber && count.canConvertToLong() && count.asLong() >= 0) {
            "Jev response contains missing or invalid usage"
        }
        return count.asLong()
    }
}
