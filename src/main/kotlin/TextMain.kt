package com.dumch

import com.dumch.giga.GigaAgent
import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaChatAPI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

suspend fun main() {
    val agent = GigaAgent.instance(userInputFlow(), GigaChatAPI(GigaAuth))
    agent.run().collect { text -> println("agent: $text") }
}

private fun userInputFlow(): Flow<String> = flow {
    println("\nType your message or `exit` to quit")
    while (true) {
        print("> ")
        val input = readlnOrNull() ?: break
        if (input.equals("exit", ignoreCase = true)) break
        emit(input)
    }
}
