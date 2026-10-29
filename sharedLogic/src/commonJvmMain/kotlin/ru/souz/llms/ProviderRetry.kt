package ru.souz.llms

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

const val AI_TUNNEL_MAX_ATTEMPTS = 3

/** Retry returned failures only; cancellation, collector failures and host limits propagate. */
suspend fun <T> retryRequest(
    attempts: Int = AI_TUNNEL_MAX_ATTEMPTS,
    shouldRetry: (T) -> Boolean,
    delayBeforeRetry: suspend (Int, T) -> Unit = { attempt, _ -> delay(250L * (attempt + 1)) },
    request: suspend () -> T,
): T {
    require(attempts > 0)
    repeat(attempts - 1) { attempt ->
        val result = request()
        if (!shouldRetry(result)) return result
        delayBeforeRetry(attempt, result)
    }
    return request()
}

/** A fresh collection may retry until its first successful chunk reaches the consumer. */
fun retryChatStream(
    attempts: Int = AI_TUNNEL_MAX_ATTEMPTS,
    shouldRetry: (LLMResponse.Chat.Error) -> Boolean = { it.retryable },
    delayBeforeRetry: suspend (Int, LLMResponse.Chat.Error) -> Unit = { attempt, _ -> delay(250L * (attempt + 1)) },
    request: () -> Flow<LLMResponse.Chat>,
): Flow<LLMResponse.Chat> = flow {
    var emitted = false
    val failure = retryRequest(
        attempts,
        shouldRetry = { error: LLMResponse.Chat.Error? -> !emitted && error != null && shouldRetry(error) },
        delayBeforeRetry = { attempt, error -> delayBeforeRetry(attempt, requireNotNull(error)) },
    ) {
        var error: LLMResponse.Chat.Error? = null
        request().collect { response ->
            if (response is LLMResponse.Chat.Error) error = response else {
                emitted = true
                emit(response)
            }
        }
        error
    }
    failure?.let { emit(it) }
}
