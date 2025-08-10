package com.dumch

import com.dumch.giga.GigaAgent
import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaChatAPI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

private val logAgent = LoggerFactory.getLogger("Agent")

suspend fun main() {
    val agent = GigaAgent.instance(userInputFlow(), GigaChatAPI.INSTANCE)
    agent.run().collect { text -> logAgent.info(text) }
}

private fun userInputFlow(): Flow<String> = flow {
    logAgent.info("\nType your message or `exit` to quit")
    while (true) {
        print("> ")
        val input = readlnOrNull() ?: break
        if (input.equals("exit", ignoreCase = true)) break
        emit(input)
    }
}
