package ru.souz

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.agent.GraphBasedAgent
import ru.souz.di.mainDiModule

private val logAgent = LoggerFactory.getLogger("Agent")

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val agent: GraphBasedAgent by di.instance()
    userInputFlow().collect { input ->
        val response = agent.execute(input)
        logAgent.info(response)
    }
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
