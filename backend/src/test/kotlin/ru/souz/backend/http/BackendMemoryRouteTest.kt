package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.coroutines.runBlocking
import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryEvidenceType
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryObjectKind
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackendMemoryRouteTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `trusted user only sees own memory facts`() = testApplication {
        val context = routeTestContext()
        val userAFactId = seedLanguageFact(context, userId = "user-a", language = "ru")
        seedLanguageFact(context, userId = "user-b", language = "en")
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                onboardingService = context.onboardingService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
                memoryService = context.memoryService,
            )
        }

        val response = client.get(BackendHttpRoutes.MEMORY_FACTS) {
            header("X-User-Id", "user-a")
            header("X-Souz-Proxy-Auth", "proxy-secret")
        }
        val items = json.readTree(response.bodyAsText())["items"]

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(userAFactId), items.map { it["id"].asText() })
        assertEquals(listOf("ru"), items.map { it["objectValueText"].asText() })
    }

    @Test
    fun `forget route hides fact from later reads`() = testApplication {
        val context = routeTestContext()
        val factId = seedLanguageFact(context, userId = "user-a", language = "ru")
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                onboardingService = context.onboardingService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
                memoryService = context.memoryService,
            )
        }

        val forgetResponse = client.post(BackendHttpRoutes.memoryForget(factId)) {
            header("X-User-Id", "user-a")
            header("X-Souz-Proxy-Auth", "proxy-secret")
        }
        val factsResponse = client.get(BackendHttpRoutes.MEMORY_FACTS) {
            header("X-User-Id", "user-a")
            header("X-Souz-Proxy-Auth", "proxy-secret")
        }
        val items = json.readTree(factsResponse.bodyAsText())["items"]

        assertEquals(HttpStatusCode.OK, forgetResponse.status)
        assertEquals(HttpStatusCode.OK, factsResponse.status)
        assertTrue(items.isEmpty)
    }

    @Test
    fun `invalidate route hides fact from later reads`() = testApplication {
        val context = routeTestContext()
        val factId = seedLanguageFact(context, userId = "user-a", language = "ru")
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                onboardingService = context.onboardingService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
                memoryService = context.memoryService,
            )
        }

        val invalidateResponse = client.post(BackendHttpRoutes.memoryInvalidate(factId)) {
            header("X-User-Id", "user-a")
            header("X-Souz-Proxy-Auth", "proxy-secret")
        }
        val factsResponse = client.get(BackendHttpRoutes.MEMORY_FACTS) {
            header("X-User-Id", "user-a")
            header("X-Souz-Proxy-Auth", "proxy-secret")
        }
        val items = json.readTree(factsResponse.bodyAsText())["items"]

        assertEquals(HttpStatusCode.OK, invalidateResponse.status)
        assertEquals(HttpStatusCode.OK, factsResponse.status)
        assertTrue(items.isEmpty)
    }

    private fun seedLanguageFact(
        context: RouteTestContext,
        userId: String,
        language: String,
    ): String = runBlocking {
        val scope = MemoryScope(MemoryScopeType.USER, userId)
        val subject = context.memoryStore.resolveOrUpsertEntity(
            userId = userId,
            entity = MemoryEntityRecord(
                scope = scope,
                entityType = "USER",
                canonicalName = "current_user",
                displayName = "current_user",
                normalizedKey = "user/current_user",
            ),
        )
        val evidence = context.memoryStore.insertEvidence(
            userId = userId,
            evidence = MemoryEvidenceRecord(
                scope = scope,
                evidenceType = MemoryEvidenceType.USER_MESSAGE,
                sourceRef = "turn:$language",
                contentExcerpt = "Language is $language",
            ),
        )
        context.memoryStore.insertFact(
            userId = userId,
            fact = MemoryFactRecord(
                scope = scope,
                subjectEntityId = subject.id!!,
                predicate = "prefers_language",
                objectKind = MemoryObjectKind.TEXT,
                objectValueText = language,
                slotKey = "user.profile.language",
                confidence = 0.95,
                status = MemoryFactStatus.ACTIVE,
                reasonToStore = "seed",
                createdAt = Instant.parse("2026-05-21T10:00:00Z"),
                validFrom = Instant.parse("2026-05-21T10:00:00Z"),
            ),
            evidenceIds = listOf(evidence.id!!),
        ).id!!
    }
}
