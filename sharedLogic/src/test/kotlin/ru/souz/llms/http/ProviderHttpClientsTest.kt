package ru.souz.llms.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIOEngineConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ProviderHttpClientsTest {
    @Test
    fun `provider resources close each distinct client exactly once`() {
        val standard = mockk<HttpClient>(relaxed = true)
        val openAi = mockk<HttpClient>(relaxed = true)
        val jev = mockk<HttpClient>(relaxed = true)
        val clients = ProviderHttpClients(standard = standard, openAi = openAi, jev = jev)

        clients.close()
        clients.close()

        verify(exactly = 1) { standard.close() }
        verify(exactly = 1) { openAi.close() }
        verify(exactly = 1) { jev.close() }
    }

    @Test
    fun `same client instance is not closed twice`() {
        val client = mockk<HttpClient>(relaxed = true)
        val clients = ProviderHttpClients(standard = client, openAi = client)
        assertSame(client, clients.jev)

        clients.close()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `both close failures are preserved`() {
        val standardFailure = IllegalStateException("standard close failed")
        val openAiFailure = IllegalArgumentException("openAi close failed")
        val standard = mockk<HttpClient>(relaxed = true)
        val openAi = mockk<HttpClient>(relaxed = true)
        val jev = mockk<HttpClient>(relaxed = true)
        every { standard.close() } throws standardFailure
        every { openAi.close() } throws openAiFailure
        val clients = ProviderHttpClients(standard = standard, openAi = openAi, jev = jev)
        assertSame(jev, clients.jev)

        val thrown = assertFailsWith<IllegalStateException> { clients.close() }

        assertSame(standardFailure, thrown)
        assertSame(openAiFailure, thrown.suppressed.single())
        verify(exactly = 1) { standard.close() }
        verify(exactly = 1) { openAi.close() }
        verify(exactly = 1) { jev.close() }
    }

    @Test
    fun `Jev configuration is isolated and owned by the host resource`() = runBlocking {
        val clients = ProviderHttpClients()
        val standardConfig = clients.standard.engine.config as CIOEngineConfig
        val jev = clients.jev
        assertNotSame(clients.standard, jev)
        assertEquals(5_000L, standardConfig.endpoint.keepAliveTime)
        clients.close()
        clients.close()
        withTimeout(5_000) {
            jev.coroutineContext.job.join()
            jev.engine.coroutineContext.job.join()
        }
        assertFalse(jev.engine.coroutineContext.job.isActive)
    }

    @Test
    fun `shutdown does not create an unused Jev transport and prevents later creation`() {
        val standard = mockk<HttpClient>(relaxed = true)
        val openAi = mockk<HttpClient>(relaxed = true)
        val clients = ProviderHttpClients(standard, openAi, jev = null)
        clients.close()
        assertFailsWith<IllegalStateException> { clients.jev }
        verify(exactly = 1) { standard.close() }
        verify(exactly = 1) { openAi.close() }
    }

    @Test
    fun `Jev idle timeout accepts positive milliseconds and rejects invalid values`() {
        for (unset in listOf(null, "", " ")) assertEquals(5_000L, jevKeepAliveTimeMillis(unset))
        assertEquals(60_000L, jevKeepAliveTimeMillis(" 60000 "))
        for (invalid in listOf("0", "-1", "60_000", "1.5", "invalid", "9223372036854775808")) {
            assertFailsWith<IllegalArgumentException> { jevKeepAliveTimeMillis(invalid) }
        }
        assertFailsWith<IllegalArgumentException> { createJevProviderHttpClient(0) }
        createJevProviderHttpClient(60_000L).use { client ->
            assertEquals(60_000L, (client.engine.config as CIOEngineConfig).endpoint.keepAliveTime)
        }
    }

    @Test
    fun `Giga resource closes exactly once`() {
        val client = mockk<HttpClient>(relaxed = true)
        val resource = GigaHttpClientResource(client)

        resource.close()
        resource.close()

        verify(exactly = 1) { client.close() }
    }
}
