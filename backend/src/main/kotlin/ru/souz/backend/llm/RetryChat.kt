package ru.souz.backend.llm

import kotlin.math.min
import kotlinx.coroutines.delay
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmMessageApi

internal const val HTTP_TOO_MANY_REQUESTS = 429

private val RETRY_AFTER = Regex("""retry-after=(\d+)""", RegexOption.IGNORE_CASE)

/** Retries [request] on HTTP 429 per [retryPolicy], honoring a `retry-after=<seconds>` hint in the error message. */
internal suspend fun retryChat(
    retryPolicy: BackendProviderRetryPolicy,
    delayMillis: suspend (Long) -> Unit,
    request: suspend () -> LLMResponse.Chat,
): LLMResponse.Chat {
    var attempt = 0
    while (true) {
        val response = request()
        if (response !is LLMResponse.Chat.Error || response.status != HTTP_TOO_MANY_REQUESTS) {
            return response
        }
        if (attempt == retryPolicy.max429Retries) return response
        delayMillis(backoffForAttempt(retryPolicy, attempt, response.message))
        attempt += 1
    }
}

internal fun backoffForAttempt(retryPolicy: BackendProviderRetryPolicy, attempt: Int, message: String): Long {
    val retryAfter = RETRY_AFTER.find(message)?.groupValues?.getOrNull(1)?.toLongOrNull()
    if (retryAfter != null) return min(retryAfter, retryPolicy.backoffMaxMs)
    return min(retryPolicy.backoffBaseMs * (attempt + 1), retryPolicy.backoffMaxMs)
}

/** Wraps a message-only LLM client with the same 429 retry/backoff as [BackendExecutionLlmChatApi]. */
internal class RetryingLlmMessageApi(
    private val delegate: LlmMessageApi,
    private val retryPolicy: BackendProviderRetryPolicy,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : LlmMessageApi {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        retryChat(retryPolicy, delayMillis) { delegate.message(body) }
}
