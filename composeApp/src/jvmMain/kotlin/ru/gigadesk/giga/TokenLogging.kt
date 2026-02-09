@file:OptIn(ExperimentalAtomicApi::class)

package ru.gigadesk.giga

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

interface TokenLogging {
    fun logTokenUsage(result: GigaResponse.Chat.Ok, body: GigaRequest.Chat)

    fun sessionTokenUsage(): GigaResponse.Usage = GigaResponse.Usage(0, 0, 0, 0)
}

class SessionTokenLogging(
    private val logObjectMapper: ObjectMapper,
) : TokenLogging {
    private val currentSessionTokensUsage = AtomicReference(GigaResponse.Usage(0, 0, 0, 0))

    override fun logTokenUsage(result: GigaResponse.Chat.Ok, body: GigaRequest.Chat) {
        val newCurrentTokensUsage = currentSessionTokensUsage.load() + result.usage
        currentSessionTokensUsage.store(newCurrentTokensUsage)

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

    override fun sessionTokenUsage(): GigaResponse.Usage = currentSessionTokensUsage.load()
}
