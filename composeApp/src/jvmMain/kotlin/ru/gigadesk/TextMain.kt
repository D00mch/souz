package ru.gigadesk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.agent.nodes.NodesClassification
import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.giga.GigaAgent
import ru.gigadesk.giga.GigaRestChatAPI

private val logAgent = LoggerFactory.getLogger("Agent")

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val desktopInfoRepo: DesktopInfoRepository by di.instance()
    val nodesClassification: NodesClassification by di.instance()
    val agent = GigaAgent.instance(
        userInputFlow(),
        GigaRestChatAPI.INSTANCE,
        desktopInfoRepo,
        nodesClassification,
    )
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
