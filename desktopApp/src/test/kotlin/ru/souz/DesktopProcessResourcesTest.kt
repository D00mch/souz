package ru.souz

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import ru.souz.llms.http.GigaHttpClientResource
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.service.mcp.McpClientManager
import ru.souz.service.telegram.TelegramBotController

class DesktopProcessResourcesTest {
    @Test
    fun `process resources close exactly once and continue after a close failure`() {
        val applicationJob = Job()
        val localLlamaRuntime = mockk<LocalLlamaRuntime>()
        val mcpClientManager = mockk<McpClientManager>(relaxed = true)
        val telegramBotController = mockk<TelegramBotController>(relaxed = true)
        val providerHttpClients = mockk<ProviderHttpClients>(relaxed = true)
        val gigaHttpClientResource = mockk<GigaHttpClientResource>(relaxed = true)
        var afterCloseCalls = 0
        every { localLlamaRuntime.close() } throws IllegalStateException("local close failed")
        val resources = DesktopProcessResources(
            applicationScope = CoroutineScope(applicationJob + Dispatchers.Default),
            localLlamaRuntime = localLlamaRuntime,
            mcpClientManager = mcpClientManager,
            telegramBotController = telegramBotController,
            providerHttpClients = providerHttpClients,
            gigaHttpClientResource = gigaHttpClientResource,
            afterClose = { afterCloseCalls += 1 },
        )

        resources.close()
        resources.close()

        assertFalse(applicationJob.isActive)
        verify(exactly = 1) { localLlamaRuntime.close() }
        verify(exactly = 1) { mcpClientManager.close() }
        verify(exactly = 1) { telegramBotController.close() }
        verify(exactly = 1) { providerHttpClients.close() }
        verify(exactly = 1) { gigaHttpClientResource.close() }
        assertEquals(1, afterCloseCalls)
    }
}
