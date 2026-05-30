package ru.souz.backend.llm

import java.io.File
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider

class RetryingLlmChatApi(
    private val delegate: LLMChatAPI,
    @Suppress("unused") private val provider: LlmProvider,
    private val retryPolicy: BackendProviderRetryPolicy,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        var attempt = 0
        var lastError: LLMResponse.Chat.Error? = null
        while (attempt <= retryPolicy.max429Retries) {
            when (val response = delegate.message(body)) {
                is LLMResponse.Chat.Ok -> return response
                is LLMResponse.Chat.Error -> {
                    lastError = response
                    if (response.status != 429 || attempt == retryPolicy.max429Retries) {
                        return response
                    }
                    delayMillis(backoffForAttempt(attempt, response.message))
                    attempt += 1
                }
            }
        }
        return lastError ?: LLMResponse.Chat.Error(429, "Rate limited.")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> {
        var attempt = 0
        while (attempt <= retryPolicy.max429Retries) {
            val items = delegate.messageStream(body).toList()
            val first = items.firstOrNull()
            if (first !is LLMResponse.Chat.Error || first.status != 429 || items.size > 1 || attempt == retryPolicy.max429Retries) {
                return flowOf(*items.toTypedArray())
            }
            delayMillis(backoffForAttempt(attempt, first.message))
            attempt += 1
        }
        return flow { }
    }

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        delegate.embeddings(body)

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        delegate.uploadFile(file)

    override suspend fun downloadFile(fileId: String): String? =
        delegate.downloadFile(fileId)

    override suspend fun balance(): LLMResponse.Balance =
        delegate.balance()

    private fun backoffForAttempt(
        attempt: Int,
        message: String,
    ): Long {
        val retryAfter = parseRetryAfterMillis(message)
        if (retryAfter != null) {
            return min(retryAfter, retryPolicy.backoffMaxMs)
        }
        val exponential = retryPolicy.backoffBaseMs * (attempt + 1)
        return min(exponential, retryPolicy.backoffMaxMs)
    }

    private fun parseRetryAfterMillis(message: String): Long? {
        val marker = Regex("""retry-after=(\d+)""", RegexOption.IGNORE_CASE)
        return marker.find(message)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }
}
