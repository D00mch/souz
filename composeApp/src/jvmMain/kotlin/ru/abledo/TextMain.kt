package ru.abledo

import ru.abledo.db.DesktopInfoRepository
import ru.abledo.db.VectorDB
import ru.abledo.giga.GigaAgent
import ru.abledo.giga.GigaRestChatAPI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

private val logAgent = LoggerFactory.getLogger("Agent")

suspend fun main() {
    val desktopInfoRepo = DesktopInfoRepository(GigaRestChatAPI.INSTANCE, VectorDB)
    val agent = GigaAgent.instance(userInputFlow(), GigaRestChatAPI.INSTANCE, desktopInfoRepo)
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
