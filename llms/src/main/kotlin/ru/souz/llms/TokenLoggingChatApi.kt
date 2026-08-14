package ru.souz.llms

import kotlinx.coroutines.CancellationException

/** Adds interactive-host token accounting without coupling provider transports to UI observability. */
class TokenLoggingChatApi(
    private val delegate: LLMChatAPI,
    private val tokenLogging: TokenLogging,
    private val onLoggingFailure: (Throwable) -> Unit = {},
) : LLMChatAPI by delegate {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        val response = delegate.message(body)
        if (response is LLMResponse.Chat.Ok) {
            try {
                tokenLogging.logTokenUsage(response, body)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                try {
                    onLoggingFailure(failure)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // A diagnostic observer cannot turn an otherwise successful model call into a failure.
                }
            }
        }
        return response
    }
}
