package com.dumch

import com.dumch.giga.GigaAgent
import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaChatAPI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val AGENT_ALIAS = "🪐"

suspend fun main() {
    val gigaApi = GigaChatAPI(GigaAuth)
    val agent = GigaAgent.instance(userInputFlow(), gigaApi)
    agent.run().collect { text -> print("$AGENT_ALIAS: $text") }
}

private fun userInputFlow(): Flow<String> = flow {
    println("Type `exit` to quit")
    while (true) {
        print("> ")
        val input = readLine() ?: break
        if (input.lowercase() == "exit") break
        emit(input)
        println("\n")
    }
}
