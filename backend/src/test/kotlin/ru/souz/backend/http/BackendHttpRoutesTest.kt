package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackendHttpRoutesTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `route helpers build the published backend contract`() {
        val chatId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val executionId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val optionId = UUID.fromString("33333333-3333-3333-3333-333333333333")

        assertEquals("/", BackendHttpRoutes.ROOT)
        assertEquals("/health", BackendHttpRoutes.HEALTH)
        assertEquals("/agent", BackendHttpRoutes.LEGACY_AGENT)
        assertEquals("/v1/bootstrap", BackendHttpRoutes.BOOTSTRAP)
        assertEquals("/v1/me/settings", BackendHttpRoutes.SETTINGS)
        assertEquals("/v1/me/provider-keys", BackendHttpRoutes.PROVIDER_KEYS)
        assertEquals("/v1/me/provider-keys/openai", BackendHttpRoutes.providerKey("openai"))
        assertEquals("/v1/chats", BackendHttpRoutes.CHATS)
        assertEquals("/v1/chats/$chatId/title", BackendHttpRoutes.chatTitle(chatId))
        assertEquals("/v1/chats/$chatId/archive", BackendHttpRoutes.archiveChat(chatId))
        assertEquals("/v1/chats/$chatId/unarchive", BackendHttpRoutes.unarchiveChat(chatId))
        assertEquals("/v1/chats/$chatId/messages", BackendHttpRoutes.chatMessages(chatId))
        assertEquals("/v1/chats/$chatId/events", BackendHttpRoutes.chatEvents(chatId))
        assertEquals("/v1/chats/$chatId/ws", BackendHttpRoutes.chatWebSocket(chatId))
        assertEquals("/v1/chats/$chatId/cancel-active", BackendHttpRoutes.cancelActive(chatId))
        assertEquals(
            "/v1/chats/$chatId/executions/$executionId/cancel",
            BackendHttpRoutes.cancelExecution(chatId, executionId),
        )
        assertEquals("/v1/options/$optionId/answer", BackendHttpRoutes.optionAnswer(optionId))
    }

    @Test
    fun `root route advertises endpoints from shared route constants`() = testApplication {
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

        val response = client.get(BackendHttpRoutes.ROOT)
        val payload = json.readTree(response.bodyAsText())
        val endpoints = payload["endpoints"].map { it.asText() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(endpoints.contains("GET ${BackendHttpRoutes.HEALTH}"))
        assertTrue(endpoints.contains("GET ${BackendHttpRoutes.BOOTSTRAP}"))
        assertTrue(endpoints.contains("GET ${BackendHttpRoutes.SETTINGS}"))
        assertTrue(endpoints.contains("PATCH ${BackendHttpRoutes.SETTINGS}"))
        assertTrue(endpoints.contains("GET ${BackendHttpRoutes.PROVIDER_KEYS}"))
        assertTrue(endpoints.contains("PUT ${BackendHttpRoutes.PROVIDER_KEY_PATTERN}"))
        assertTrue(endpoints.contains("DELETE ${BackendHttpRoutes.PROVIDER_KEY_PATTERN}"))
        assertTrue(endpoints.contains("GET ${BackendHttpRoutes.CHATS}"))
        assertTrue(endpoints.contains("POST ${BackendHttpRoutes.CHATS}"))
        assertTrue(endpoints.contains("PATCH ${BackendHttpRoutes.CHAT_TITLE_PATTERN}"))
        assertTrue(endpoints.contains("POST ${BackendHttpRoutes.CHAT_ARCHIVE_PATTERN}"))
        assertTrue(endpoints.contains("POST ${BackendHttpRoutes.CHAT_UNARCHIVE_PATTERN}"))
        assertTrue(endpoints.contains("GET ${BackendHttpRoutes.CHAT_MESSAGES_PATTERN}"))
        assertTrue(endpoints.contains("GET ${BackendHttpRoutes.CHAT_EVENTS_PATTERN}"))
        assertTrue(endpoints.contains("POST ${BackendHttpRoutes.CHAT_MESSAGES_PATTERN}"))
        assertTrue(endpoints.contains("POST ${BackendHttpRoutes.CHAT_CANCEL_ACTIVE_PATTERN}"))
        assertTrue(endpoints.contains("POST ${BackendHttpRoutes.CHAT_EXECUTION_CANCEL_PATTERN}"))
        assertTrue(endpoints.contains("POST ${BackendHttpRoutes.OPTION_ANSWER_PATTERN}"))
        assertTrue(endpoints.contains("WS ${BackendHttpRoutes.CHAT_WS_PATTERN}"))
        assertTrue(endpoints.contains("POST ${BackendHttpRoutes.LEGACY_AGENT}"))
    }
}
