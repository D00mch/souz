package ru.souz

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.agent.AgentFacade
import ru.souz.di.mainDiModule
import ru.souz.llms.http.GigaHttpClientResource
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.service.mcp.McpClientManager

private val logAgent = LoggerFactory.getLogger("Agent")

suspend fun main() {
    val di = DI.invoke { import(mainDiModule, allowOverride = true) }
    val agent: AgentFacade by di.instance()
    val applicationScope: CoroutineScope by di.instance()
    val localLlamaRuntime: LocalLlamaRuntime by di.instance()
    val mcpClientManager: McpClientManager by di.instance()
    val providerHttpClients: ProviderHttpClients by di.instance()
    val gigaHttpClientResource: GigaHttpClientResource by di.instance()
    val processResources = DesktopProcessResources(
        applicationScope = applicationScope,
        localLlamaRuntime = localLlamaRuntime,
        mcpClientManager = mcpClientManager,
        providerHttpClients = providerHttpClients,
        gigaHttpClientResource = gigaHttpClientResource,
    )

    try {
        userInputFlow().collect { input ->
            val response = agent.execute(input)
            logAgent.info(response)
        }
    } finally {
        processResources.close()
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
