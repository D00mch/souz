package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.testutil.rawEventPayload

private val stage6Json = jacksonObjectMapper()

class BackendStage6EventRouteTest {
    @Test
    fun `http replay returns only owned chat events after requested seq`() = testApplication {
        val context = stage6RouteTestContext()
        val ownedChat = chat(userId = "user-a", title = "Owned")
        val foreignChat = chat(userId = "user-b", title = "Foreign")
        runBlocking {
            context.chatRepository.create(ownedChat)
            context.chatRepository.create(foreignChat)
            context.eventRepository.append(
                userId = "user-a",
                chatId = ownedChat.id,
                executionId = null,
                type = AgentEventType.EXECUTION_STARTED,
                payload = rawEventPayload("executionId" to "a-1"),
                createdAt = Instant.parse("2026-05-01T10:00:00Z"),
            )
            context.eventRepository.append(
                userId = "user-a",
                chatId = ownedChat.id,
                executionId = null,
                type = AgentEventType.MESSAGE_CREATED,
                payload = rawEventPayload("messageId" to "m-2"),
                createdAt = Instant.parse("2026-05-01T10:00:01Z"),
            )
            context.eventRepository.append(
                userId = "user-b",
                chatId = foreignChat.id,
                executionId = null,
                type = AgentEventType.EXECUTION_STARTED,
                payload = rawEventPayload("executionId" to "b-1"),
                createdAt = Instant.parse("2026-05-01T10:00:02Z"),
            )
        }
        installStage6Application(context)

        val response = client.get("${BackendHttpRoutes.chatEvents(ownedChat.id)}?afterSeq=1") {
            trustedHeaders("user-a")
        }
        val payload = stage6Json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, payload["items"].size())
        assertEquals(2L, payload["items"][0]["seq"].asLong())
        assertEquals(ownedChat.id.toString(), payload["items"][0]["chatId"].asText())
        assertEquals("message.created", payload["items"][0]["type"].asText())
    }

    @Test
    fun `http replay route defaults clamps and validates limit`() = testApplication {
        val context = stage6RouteTestContext()
        val chat = chat(userId = "user-a", title = "Many events")
        runBlocking {
            context.chatRepository.create(chat)
            repeat(1_005) { index ->
                context.eventRepository.append(
                    userId = "user-a",
                    chatId = chat.id,
                    executionId = null,
                    type = AgentEventType.MESSAGE_CREATED,
                    payload = rawEventPayload("index" to index.toString()),
                    createdAt = Instant.parse("2026-05-01T10:00:00Z").plusSeconds(index.toLong()),
                )
            }
        }
        installStage6Application(context)

        val defaultResponse = client.get(BackendHttpRoutes.chatEvents(chat.id)) {
            trustedHeaders("user-a")
        }
        val clampedResponse = client.get("${BackendHttpRoutes.chatEvents(chat.id)}?limit=9999") {
            trustedHeaders("user-a")
        }
        val pagedResponse = client.get("${BackendHttpRoutes.chatEvents(chat.id)}?afterSeq=1000&limit=9999") {
            trustedHeaders("user-a")
        }
        val zeroResponse = client.get("${BackendHttpRoutes.chatEvents(chat.id)}?limit=0") {
            trustedHeaders("user-a")
        }
        val negativeResponse = client.get("${BackendHttpRoutes.chatEvents(chat.id)}?limit=-1") {
            trustedHeaders("user-a")
        }

        val defaultItems = stage6Json.readTree(defaultResponse.bodyAsText())["items"]
        val clampedItems = stage6Json.readTree(clampedResponse.bodyAsText())["items"]
        val pagedItems = stage6Json.readTree(pagedResponse.bodyAsText())["items"]

        assertEquals(HttpStatusCode.OK, defaultResponse.status)
        assertEquals(100, defaultItems.size())
        assertEquals(100L, defaultItems.last()["seq"].asLong())

        assertEquals(HttpStatusCode.OK, clampedResponse.status)
        assertEquals(1_000, clampedItems.size())
        assertEquals(1_000L, clampedItems.last()["seq"].asLong())

        assertEquals(HttpStatusCode.OK, pagedResponse.status)
        assertEquals(5, pagedItems.size())
        assertEquals(1_001L, pagedItems.first()["seq"].asLong())
        assertEquals(1_005L, pagedItems.last()["seq"].asLong())

        assertEquals(HttpStatusCode.BadRequest, zeroResponse.status)
        assertEquals("invalid_request", stage6Json.readTree(zeroResponse.bodyAsText())["error"]["code"].asText())

        assertEquals(HttpStatusCode.BadRequest, negativeResponse.status)
        assertEquals("invalid_request", stage6Json.readTree(negativeResponse.bodyAsText())["error"]["code"].asText())
    }

    @Test
    fun `http replay route is a controlled error when ws events are disabled`() = testApplication {
        val context = routeTestContext(
            featureFlags = BackendFeatureFlags(
                wsEvents = false,
                streamingMessages = true,
                toolEvents = true,
            ),
        )
        val chat = chat(userId = "user-a", title = "Flags")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage6Application(context)

        val response = client.get("${BackendHttpRoutes.chatEvents(chat.id)}?afterSeq=0") {
            trustedHeaders("user-a")
        }
        val payload = stage6Json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("feature_disabled", payload["error"]["code"].asText())
    }
}

private fun ApplicationTestBuilder.installStage6Application(context: RouteTestContext) {
    application {
        backendApplication(
            BackendHttpDependencies(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
                eventService = context.eventService,
                featureFlags = context.featureFlags,
            )
        )
    }
}

private fun stage6RouteTestContext(): RouteTestContext =
    routeTestContext(
        featureFlags = BackendFeatureFlags(
            wsEvents = true,
            streamingMessages = true,
            toolEvents = true,
        ),
    )
