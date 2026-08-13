package ru.souz.backend.llm

import kotlinx.coroutines.job
import ru.souz.llms.LlmProvider
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackendProviderHttpClientsTest {
    @Test
    fun `pool reuses provider client and closes it with application resources`() {
        val clients = BackendProviderHttpClients()
        val openAiClient = clients.clientFor(LlmProvider.OPENAI)

        assertSame(openAiClient, clients.clientFor(LlmProvider.OPENAI))

        clients.close()
        clients.close()

        assertTrue(openAiClient.coroutineContext.job.isCompleted)
        assertFailsWith<IllegalStateException> {
            clients.clientFor(LlmProvider.OPENAI)
        }
    }
}
