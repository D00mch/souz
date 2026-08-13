package ru.souz

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import ru.souz.llms.http.GigaHttpClientResource
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.service.mcp.McpClientManager
import ru.souz.service.telegram.TelegramBotController

class DesktopProcessResourcesTest {
    @Test
    fun `process shutdown cancels and joins work before resources close exactly once`() = runTest {
        val order = mutableListOf<String>()
        val applicationJob = Job().also { job ->
            job.invokeOnCompletion { order += "application-work" }
        }
        val localLlamaRuntime = mockk<LocalLlamaRuntime>()
        val mcpClientManager = mockk<McpClientManager>()
        val telegramBotController = mockk<TelegramBotController>()
        val providerHttpClients = mockk<ProviderHttpClients>()
        val gigaHttpClientResource = mockk<GigaHttpClientResource>()
        every { localLlamaRuntime.close() } answers { order += "local-runtime" }
        every { mcpClientManager.close() } answers { order += "mcp" }
        every { telegramBotController.close() } answers { order += "telegram" }
        every { providerHttpClients.close() } answers { order += "provider-http" }
        every { gigaHttpClientResource.close() } answers { order += "giga-http" }
        val resources = DesktopProcessResources(
            applicationScope = CoroutineScope(applicationJob + Dispatchers.Default),
            localLlamaRuntime = localLlamaRuntime,
            mcpClientManager = mcpClientManager,
            telegramBotController = telegramBotController,
            providerHttpClients = providerHttpClients,
            gigaHttpClientResource = gigaHttpClientResource,
            afterClose = { order += "observer" },
        )

        resources.shutdown()
        resources.shutdown()

        assertFalse(applicationJob.isActive)
        assertEquals(
            listOf("application-work", "local-runtime", "mcp", "telegram", "provider-http", "giga-http", "observer"),
            order,
        )
        verify(exactly = 1) { localLlamaRuntime.close() }
        verify(exactly = 1) { mcpClientManager.close() }
        verify(exactly = 1) { telegramBotController.close() }
        verify(exactly = 1) { providerHttpClients.close() }
        verify(exactly = 1) { gigaHttpClientResource.close() }
    }

    @Test
    fun `desktop shutdown aggregates failures in resource order`() = runTest {
        val localFailure = IllegalStateException("local close failed")
        val providerFailure = IllegalArgumentException("provider close failed")
        val localLlamaRuntime = mockk<LocalLlamaRuntime>()
        val mcpClientManager = mockk<McpClientManager>(relaxed = true)
        val providerHttpClients = mockk<ProviderHttpClients>()
        val gigaHttpClientResource = mockk<GigaHttpClientResource>(relaxed = true)
        every { localLlamaRuntime.close() } throws localFailure
        every { providerHttpClients.close() } throws providerFailure
        val resources = DesktopProcessResources(
            applicationScope = CoroutineScope(Job() + Dispatchers.Default),
            localLlamaRuntime = localLlamaRuntime,
            mcpClientManager = mcpClientManager,
            providerHttpClients = providerHttpClients,
            gigaHttpClientResource = gigaHttpClientResource,
        )

        val thrown = assertFailsWith<IllegalStateException> { resources.shutdown() }
        val repeated = assertFailsWith<IllegalStateException> { resources.shutdown() }

        listOf(thrown, repeated).forEach { observed ->
            assertEquals("local close failed", observed.message)
            assertEquals(listOf("provider close failed"), observed.suppressed.map { it.message })
        }
        verify(exactly = 1) { localLlamaRuntime.close() }
        verify(exactly = 1) { providerHttpClients.close() }
    }
}
