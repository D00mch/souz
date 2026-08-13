package ru.souz.llms.http

import io.ktor.client.HttpClient
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ProviderHttpClientsTest {
    @Test
    fun `provider resources close each distinct client exactly once`() {
        val standard = mockk<HttpClient>(relaxed = true)
        val openAi = mockk<HttpClient>(relaxed = true)
        val clients = ProviderHttpClients(standard = standard, openAi = openAi)

        clients.close()
        clients.close()

        verify(exactly = 1) { standard.close() }
        verify(exactly = 1) { openAi.close() }
    }

    @Test
    fun `same client instance is not closed twice`() {
        val client = mockk<HttpClient>(relaxed = true)
        val clients = ProviderHttpClients(standard = client, openAi = client)

        clients.close()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `both close failures are preserved`() {
        val standardFailure = IllegalStateException("standard close failed")
        val openAiFailure = IllegalArgumentException("openAi close failed")
        val standard = mockk<HttpClient>(relaxed = true)
        val openAi = mockk<HttpClient>(relaxed = true)
        every { standard.close() } throws standardFailure
        every { openAi.close() } throws openAiFailure
        val clients = ProviderHttpClients(standard = standard, openAi = openAi)

        val thrown = assertFailsWith<IllegalStateException> { clients.close() }

        assertSame(standardFailure, thrown)
        assertSame(openAiFailure, thrown.suppressed.single())
        verify(exactly = 1) { standard.close() }
        verify(exactly = 1) { openAi.close() }
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
