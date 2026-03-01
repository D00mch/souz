package ru.souz.giga

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

interface TokenLogging {
    fun startRequest(requestId: String) = Unit
    fun finishRequest(requestId: String) = Unit
    fun requestContextElement(requestId: String): CoroutineContext = EmptyCoroutineContext
    fun logTokenUsage(result: GigaResponse.Chat.Ok, body: GigaRequest.Chat)

    fun currentRequestTokenUsage(requestId: String): GigaResponse.Usage = GigaResponse.Usage(0, 0, 0, 0)
    fun sessionTokenUsage(): GigaResponse.Usage = GigaResponse.Usage(0, 0, 0, 0)
}

class SessionTokenLogging(
    private val logObjectMapper: ObjectMapper,
) : TokenLogging {
    private val stateLock = Any()
    private val activeRequestId = ThreadLocal<String?>()
    private var currentSessionTokensUsage: GigaResponse.Usage = ZERO_USAGE
    private val requestTokenUsage = LinkedHashMap<String, GigaResponse.Usage>()

    override fun startRequest(requestId: String) {
        synchronized(stateLock) {
            requestTokenUsage[requestId] = ZERO_USAGE
        }
    }

    override fun requestContextElement(requestId: String): CoroutineContext =
        activeRequestId.asContextElement(requestId)

    override fun logTokenUsage(result: GigaResponse.Chat.Ok, body: GigaRequest.Chat) {
        val requestId = activeRequestId.get()
        val newCurrentTokensUsage = synchronized(stateLock) {
            currentSessionTokensUsage += result.usage
            requestId?.let {
                requestTokenUsage[it] = (requestTokenUsage[it] ?: ZERO_USAGE) + result.usage
            }
            currentSessionTokensUsage
        }

        val (_, _, spent, cached) = result.usage
        val (_, _, sessionSpent, sessionCached) = newCurrentTokensUsage
        println(
            """
            |--  History.len: ${body.messages.size},  Functions.len: ${body.functions.size}
            |--  Tokens spent: $spent, cached: $cached, per session spent: $sessionSpent, cached: $sessionCached
            |--  Choice.len: ${result.choices.size}, Last choice:"
            |${logObjectMapper.writeValueAsString(result.choices.lastOrNull())}
            """.trimMargin()
        )
    }

    override fun finishRequest(requestId: String) {
        synchronized(stateLock) {
            requestTokenUsage.remove(requestId)
        }
    }

    override fun currentRequestTokenUsage(requestId: String): GigaResponse.Usage =
        synchronized(stateLock) {
            requestTokenUsage[requestId] ?: ZERO_USAGE
        }

    override fun sessionTokenUsage(): GigaResponse.Usage =
        synchronized(stateLock) { currentSessionTokensUsage }

    private companion object {
        val ZERO_USAGE = GigaResponse.Usage(0, 0, 0, 0)
    }
}
