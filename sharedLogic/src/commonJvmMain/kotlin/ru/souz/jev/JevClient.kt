package ru.souz.jev

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.slf4j.LoggerFactory
import ru.souz.llms.restJsonMapper
import kotlin.time.TimeSource

private const val JEV_TIMEOUT = 2_000L

/** Reusable Noul evaluation; the caller owns the supplied HTTP client. */
class JevClient(
    private val client: HttpClient,
    private val token: String = System.getenv("JEV_TOKEN").orEmpty().trim(),
    private val model: String = System.getenv("JEV_MODEL")?.trim()?.takeIf(String::isNotEmpty) ?: "jev-latest",
    private val onHttpRequestCompleted: (JevHttpDiagnostic) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(JevClient::class.java)

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
        var status: Int? = null
        var protocol: String? = null
        var outcome = "cancelled"
        val started = TimeSource.Monotonic.markNow()
        val body = try {
            val response = client.post("https://api.typesafe.ai/v1/systemone") {
                expectSuccess = false
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                timeout { requestTimeoutMillis = JEV_TIMEOUT }
                setBody(JevRequest(state, model, questions.mapValues { JevQuestion(it.value) }))
            }
            status = response.status.value
            protocol = response.version.toString()
            val text = response.bodyAsText()
            outcome = if (response.status.isSuccess()) "success" else "http_error"
            text
        } catch (timeout: TimeoutCancellationException) {
            outcome = "timeout"
            throw timeout
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            outcome = when (error) {
                is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException -> "timeout"
                else -> "failure"
            }
            throw error
        } finally {
            val diagnostic = JevHttpDiagnostic(
                durationMs = started.elapsedNow().inWholeNanoseconds / 1_000_000.0,
                status = status,
                protocol = protocol,
                outcome = outcome,
            )
            logger.info(
                "Jev HTTP request durationMs={} status={} protocol={} outcome={}",
                diagnostic.durationMs, status, protocol, outcome
            )
            // Observability must not replace a result, timeout, or cancellation.
            runCatching { onHttpRequestCompleted(diagnostic) }
        }
        check(status in 200..299) { "Jev request failed (HTTP $status)" }
        val result = try {
            restJsonMapper.readTree(body)
        } catch (_: JsonProcessingException) {
            error("Jev returned invalid JSON")
        }
        check(result != null) { "Jev returned an empty response" }
        return questions.mapValues { (name, _) ->
            val answer = result.path("answers").path(name)
            val probability = answer.path("noul")
            val value = probability.asDouble()
            check(answer.path("type").asText() == "noul" && probability.isNumber && value in 0.0..1.0) {
                "Jev response contains a missing or invalid Noul answer"
            }
            value
        }
    }
}

/** Full HTTP duration through body receipt, before JSON parsing; no credentials or payload. */
data class JevHttpDiagnostic(
    val durationMs: Double,
    val status: Int?,
    val protocol: String?,
    val outcome: String,
)

private data class JevRequest(val state: JsonNode, val model: String, val questions: Map<String, JevQuestion>)
private data class JevQuestion(val instructions: String, val type: String = "noul")
