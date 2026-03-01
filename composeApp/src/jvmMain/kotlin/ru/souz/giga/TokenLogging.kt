@file:OptIn(ExperimentalAtomicApi::class)

package ru.souz.giga

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

interface TokenLogging {
    fun startRequest(requestId: String) = Unit
    fun finishRequest(requestId: String) = Unit
    fun logTokenUsage(result: GigaResponse.Chat.Ok, body: GigaRequest.Chat)

    fun currentRequestTokenUsage(): GigaResponse.Usage = GigaResponse.Usage(0, 0, 0, 0)
    fun sessionTokenUsage(): GigaResponse.Usage = GigaResponse.Usage(0, 0, 0, 0)
}

class SessionTokenLogging(
    private val logObjectMapper: ObjectMapper,
) : TokenLogging {
    private val currentSessionTokensUsage = AtomicReference(GigaResponse.Usage(0, 0, 0, 0))
    private val currentRequestId = AtomicReference<String?>(null)
    private val currentRequestTokensUsage = AtomicReference(GigaResponse.Usage(0, 0, 0, 0))

    override fun startRequest(requestId: String) {
        currentRequestId.store(requestId)
        currentRequestTokensUsage.store(GigaResponse.Usage(0, 0, 0, 0))
    }

    override fun logTokenUsage(result: GigaResponse.Chat.Ok, body: GigaRequest.Chat) {
        val newCurrentTokensUsage = currentSessionTokensUsage.load() + result.usage
        currentSessionTokensUsage.store(newCurrentTokensUsage)
        currentRequestId.load()?.let {
            currentRequestTokensUsage.store(currentRequestTokensUsage.load() + result.usage)
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
        if (currentRequestId.load() == requestId) {
            currentRequestId.store(null)
            currentRequestTokensUsage.store(GigaResponse.Usage(0, 0, 0, 0))
        }
    }

    override fun currentRequestTokenUsage(): GigaResponse.Usage = currentRequestTokensUsage.load()

    override fun sessionTokenUsage(): GigaResponse.Usage = currentSessionTokensUsage.load()
}
