package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.llms.LLMModel

class BackendHttpRefactorRegressionTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `legacy agent route rejects mismatched request id header`() = testApplication {
        val context = routeTestContext()
        application {
            backendApplication(
                agentService = context.agentService,
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                internalAgentToken = { "legacy-token" },
                trustedProxyToken = { "proxy-secret" },
            )
        }

        val response = client.post(BackendHttpRoutes.LEGACY_AGENT) {
            header(HttpHeaders.Authorization, "Bearer legacy-token")
            header("X-Request-Id", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "requestId": "${UUID.randomUUID()}",
                  "userId": "11111111-1111-1111-1111-111111111111",
                  "conversationId": "22222222-2222-2222-2222-222222222222",
                  "prompt": "test",
                  "model": "${LLMModel.Max.alias}",
                  "contextSize": 16000,
                  "source": "web",
                  "locale": "ru-RU",
                  "timeZone": "Europe/Moscow"
                }
                """.trimIndent()
            )
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("X-Request-Id must match requestId.", payload["error"].asText())
    }

    @Test
    fun `chat routes keep boolean query validation contract`() = testApplication {
        val context = routeTestContext()
        application {
            backendApplication(
                agentService = context.agentService,
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                internalAgentToken = { "legacy-token" },
                trustedProxyToken = { "proxy-secret" },
                chatService = context.chatService,
            )
        }

        val response = client.get("${BackendHttpRoutes.CHATS}?includeArchived=nope") {
            trustedHeaders("user-a")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", payload["error"]["code"].asText())
        assertEquals("includeArchived must be true or false.", payload["error"]["message"].asText())
    }
}
