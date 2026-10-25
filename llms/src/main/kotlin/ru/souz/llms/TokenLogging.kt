package ru.souz.llms

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

interface TokenLogging {
    fun startRequest(requestId: String) = Unit
    fun finishRequest(requestId: String) = Unit
    fun requestContextElement(requestId: String): CoroutineContext = EmptyCoroutineContext
    suspend fun logTokenUsage(result: LLMResponse.Chat.Ok, body: LLMRequest.Chat)

    fun currentRequestTokenUsage(requestId: String): LLMResponse.Usage = LLMResponse.Usage(0, 0, 0, 0)
    fun sessionTokenUsage(): LLMResponse.Usage = LLMResponse.Usage(0, 0, 0, 0)
}
