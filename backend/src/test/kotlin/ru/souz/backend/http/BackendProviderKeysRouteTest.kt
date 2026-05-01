package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.llms.LlmProvider

class BackendProviderKeysRouteTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `get provider keys returns safe metadata for current user only`() = testApplication {
        val context = routeTestContext()
        runBlocking {
            context.userProviderKeyRepository.save(
                UserProviderKey(
                    userId = "user-a",
                    provider = LlmProvider.OPENAI,
                    encryptedApiKey = "enc-openai-user-a",
                    keyHint = "...1234",
                )
            )
            context.userProviderKeyRepository.save(
                UserProviderKey(
                    userId = "user-b",
                    provider = LlmProvider.QWEN,
                    encryptedApiKey = "enc-qwen-user-b",
                    keyHint = "...5678",
                )
            )
        }
        application {
            backendApplication(
                agentService = context.agentService,
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                internalAgentToken = { "legacy-token" },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                providerKeyService = context.userProviderKeyService,
            )
        }

        val response = client.get("/v1/me/provider-keys") {
            trustedHeaders("user-a")
        }
        val rawBody = response.bodyAsText()
        val payload = json.readTree(rawBody)
        val items = payload["items"]

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, items.count { it["configured"].asBoolean() })
        assertEquals(
            "...1234",
            items.first { it["provider"].asText() == "openai" }["keyHint"].asText(),
        )
        assertFalse(rawBody.contains("enc-openai-user-a"))
        assertFalse(rawBody.contains("enc-qwen-user-b"))
    }

    @Test
    fun `put provider key encrypts secret and response never returns plaintext`() = testApplication {
        val context = routeTestContext()
        application {
            backendApplication(
                agentService = context.agentService,
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                internalAgentToken = { "legacy-token" },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                providerKeyService = context.userProviderKeyService,
            )
        }

        val response = client.put("/v1/me/provider-keys/openai") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"sk-user-a-plain-123456"}""")
        }
        val rawBody = response.bodyAsText()
        val payload = json.readTree(rawBody)
        val stored = runBlocking { context.userProviderKeyRepository.get("user-a", LlmProvider.OPENAI) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("openai", payload["providerKey"]["provider"].asText())
        assertEquals(true, payload["providerKey"]["configured"].asBoolean())
        assertEquals("...3456", payload["providerKey"]["keyHint"].asText())
        assertNotEquals("sk-user-a-plain-123456", stored?.encryptedApiKey)
        assertFalse(rawBody.contains("sk-user-a-plain-123456"))
    }

    @Test
    fun `delete provider key is scoped to current user`() = testApplication {
        val context = routeTestContext()
        runBlocking {
            context.userProviderKeyRepository.save(
                UserProviderKey(
                    userId = "user-a",
                    provider = LlmProvider.OPENAI,
                    encryptedApiKey = "enc-openai-user-a",
                    keyHint = "...1234",
                )
            )
            context.userProviderKeyRepository.save(
                UserProviderKey(
                    userId = "user-b",
                    provider = LlmProvider.OPENAI,
                    encryptedApiKey = "enc-openai-user-b",
                    keyHint = "...9876",
                )
            )
        }
        application {
            backendApplication(
                agentService = context.agentService,
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                internalAgentToken = { "legacy-token" },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                providerKeyService = context.userProviderKeyService,
            )
        }

        val response = client.delete("/v1/me/provider-keys/openai") {
            trustedHeaders("user-a")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(runBlocking { context.userProviderKeyRepository.get("user-a", LlmProvider.OPENAI) })
        assertEquals(
            "...9876",
            runBlocking { context.userProviderKeyRepository.get("user-b", LlmProvider.OPENAI) }?.keyHint,
        )
    }
}
