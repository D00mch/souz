package ru.souz.llms

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.asContextElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
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
    private val activeRequestId = ThreadLocal<String?>()
    private val currentSessionTokensUsage = AtomicReference(ZERO_USAGE)
    private val requestTokenUsage = ConcurrentHashMap<String, GigaResponse.Usage>()

    override fun startRequest(requestId: String) {
        requestTokenUsage[requestId] = ZERO_USAGE
    }

    override fun requestContextElement(requestId: String): CoroutineContext =
        activeRequestId.asContextElement(requestId)

    override fun logTokenUsage(result: GigaResponse.Chat.Ok, body: GigaRequest.Chat) {
        val requestId = activeRequestId.get()
        val newCurrentTokensUsage = currentSessionTokensUsage.updateAndGet { it + result.usage }
        requestId?.let { id ->
            requestTokenUsage.compute(id) { _, currentUsage ->
                (currentUsage ?: ZERO_USAGE) + result.usage
            }
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
        requestTokenUsage.remove(requestId)
    }

    override fun currentRequestTokenUsage(requestId: String): GigaResponse.Usage =
        requestTokenUsage[requestId] ?: ZERO_USAGE

    override fun sessionTokenUsage(): GigaResponse.Usage =
        currentSessionTokensUsage.get()

    private companion object {
        val ZERO_USAGE = GigaResponse.Usage(0, 0, 0, 0)
    }
}
