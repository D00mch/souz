package ru.souz.frida

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonProcessingException
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
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.llms.restJsonMapper
import kotlin.time.TimeSource

private const val FRIDA_TIMEOUT = 2_000L
private const val CHOICE_QUESTION = "choice"

/**
 * FRIDA-Decisions through its vLLM `POST /pooling` endpoint (`{"model", "data": request}` in,
 * `{"data": {"answers", "margins", ...}}` out). The caller owns the supplied HTTP client.
 */
class FridaClient(
    private val client: HttpClient,
    private val url: String = System.getenv("FRIDA_URL").orEmpty().trim(),
    private val token: String? = System.getenv("FRIDA_TOKEN")?.trim()?.takeIf(String::isNotEmpty),
    private val model: String? = System.getenv("FRIDA_MODEL")?.trim()?.takeIf(String::isNotEmpty),
) {
    private val logger = LoggerFactory.getLogger(FridaClient::class.java)

    init {
        require(url.isNotBlank()) { "FRIDA_URL is required when using FRIDA" }
    }

    /** Asks one choice question and returns every option's raw margin by its key. */
    suspend fun choose(state: String, instructions: String, options: Map<String, String>): Map<String, Double> {
        if (options.isEmpty()) return emptyMap()
        var status: Int? = null
        var outcome = "cancelled"
        val started = TimeSource.Monotonic.markNow()
        val body = try {
            val response = client.post(url) {
                expectSuccess = false
                token?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                timeout { requestTimeoutMillis = FRIDA_TIMEOUT }
                setBody(FridaRequest(model, FridaData(state, mapOf(CHOICE_QUESTION to FridaQuestion(instructions, options)))))
            }
            status = response.status.value
            outcome = if (response.status.isSuccess()) "success" else "http_error"
            response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            outcome = "failure"
            throw error
        } finally {
            logger.info("FRIDA HTTP request durationMs={} status={} outcome={}", started.elapsedNow().inWholeMilliseconds, status, outcome)
        }
        check(status in 200..299) { "FRIDA request failed (HTTP $status)" }
        val margins = try {
            restJsonMapper.readTree(body)
        } catch (_: JsonProcessingException) {
            error("FRIDA returned invalid JSON")
        }?.path("data")?.path("margins")?.path(CHOICE_QUESTION)
        check(margins != null && options.keys.all { margins.path(it).isNumber && margins.path(it).asDouble().isFinite() }) {
            "FRIDA response contains missing or invalid margins"
        }
        return options.mapValues { (option, _) -> margins.path(option).asDouble() }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
private data class FridaRequest(val model: String?, val data: FridaData)
private data class FridaData(val state: String, val questions: Map<String, FridaQuestion>)
private data class FridaQuestion(val instructions: String, val criteria: Map<String, String>, val type: String = "choice")
