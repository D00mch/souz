package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import ru.souz.backend.TestSettingsProvider
import ru.souz.llms.LLMModel

class BackendOnboardingRouteTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `brand new user gets onboarding state without provider keys or persisted settings`() = testApplication {
        val context = routeTestContext(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = null
                qwenChatKey = null
                aiTunnelKey = null
                anthropicKey = null
                openaiKey = null
                contextSize = 32_000
                useStreaming = true
            },
        )
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                providerKeyService = context.userProviderKeyService,
                onboardingService = context.onboardingService,
            )
        }

        val response = client.get(BackendHttpRoutes.ONBOARDING_STATE) {
            trustedHeaders("brand-new-user")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(true, payload["required"].asBoolean())
        assertEquals(false, payload["completed"].asBoolean())
        assertEquals("provider", payload["currentStep"].asText())
        assertEquals(false, payload["hasUsableModelAccess"].asBoolean())
        assertTrue(payload["reasons"].any { it.asText() == "missing_model_access" })
        assertTrue(payload["availableServerManagedProviders"].isArray)
        assertTrue(payload["availableUserManagedProviders"].isArray)
        assertTrue(payload["availableUserManagedProviders"].size() > 0)
        assertEquals(32_000, payload["currentSettings"]["contextSize"].asInt())
        assertTrue(payload["currentSettings"].has("defaultModel"))
        assertTrue(payload["currentSettings"].has("systemPrompt"))
        assertTrue(payload["currentSettings"].has("enabledTools"))
        assertTrue(payload["currentSettings"].has("showToolEvents"))
        assertTrue(payload["currentSettings"].has("streamingMessages"))
        assertTrue(payload["recommendedDefaultModel"].isTextual)
    }

    @Test
    fun `onboarding state reports usable access when server managed provider exists`() = testApplication {
        val context = routeTestContext(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
                qwenChatKey = null
                aiTunnelKey = null
                anthropicKey = null
                openaiKey = null
            },
        )
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                providerKeyService = context.userProviderKeyService,
                onboardingService = context.onboardingService,
            )
        }

        val response = client.get(BackendHttpRoutes.ONBOARDING_STATE) {
            trustedHeaders("user-with-server-access")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(true, payload["required"].asBoolean())
        assertEquals(false, payload["completed"].asBoolean())
        assertEquals("preferences", payload["currentStep"].asText())
        assertEquals(true, payload["hasUsableModelAccess"].asBoolean())
        assertTrue(payload["reasons"].any { it.asText() == "preferences_incomplete" })
        assertTrue(
            payload["availableServerManagedProviders"].any { provider ->
                provider["provider"].asText() == "giga" &&
                    provider["models"].any { it.asText() == context.settingsProvider.gigaModel.alias }
            }
        )
    }

    @Test
    fun `onboarding state exposes only user managed setup path when no server managed providers exist`() = testApplication {
        val context = routeTestContext(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = null
                qwenChatKey = null
                aiTunnelKey = null
                anthropicKey = null
                openaiKey = null
            },
        )
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                providerKeyService = context.userProviderKeyService,
                onboardingService = context.onboardingService,
            )
        }

        val response = client.get(BackendHttpRoutes.ONBOARDING_STATE) {
            trustedHeaders("user-managed-only")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, payload["availableServerManagedProviders"].size())
        assertTrue(payload["availableUserManagedProviders"].size() > 0)
        assertTrue(payload["availableUserManagedProviders"].all { !it["configured"].asBoolean() })
        assertFalse(payload["hasUsableModelAccess"].asBoolean())
    }

    @Test
    fun `completing onboarding persists completion and clears required state on next read`() = testApplication {
        val context = routeTestContext(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
                qwenChatKey = null
                aiTunnelKey = null
                anthropicKey = null
                openaiKey = null
                contextSize = 32_000
                useStreaming = true
            },
        )
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                providerKeyService = context.userProviderKeyService,
                onboardingService = context.onboardingService,
            )
        }

        val complete = client.post(BackendHttpRoutes.ONBOARDING_COMPLETE) {
            trustedHeaders("completing-user")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "defaultModel": "${context.settingsProvider.gigaModel.alias}",
                  "locale": "ru-RU",
                  "timeZone": "Europe/Moscow",
                  "streamingMessages": true,
                  "showToolEvents": true,
                  "enabledTools": ["ListFiles"]
                }
                """.trimIndent()
            )
        }
        val completePayload = json.readTree(complete.bodyAsText())
        val state = client.get(BackendHttpRoutes.ONBOARDING_STATE) {
            trustedHeaders("completing-user")
        }
        val statePayload = json.readTree(state.bodyAsText())

        assertEquals(HttpStatusCode.OK, complete.status)
        assertEquals(true, completePayload["completed"].asBoolean())

        assertEquals(HttpStatusCode.OK, state.status)
        assertEquals(false, statePayload["required"].asBoolean())
        assertEquals(true, statePayload["completed"].asBoolean())
        assertEquals("done", statePayload["currentStep"].asText())
        assertEquals(true, statePayload["hasUsableModelAccess"].asBoolean())
        assertEquals(context.settingsProvider.gigaModel.alias, statePayload["currentSettings"]["defaultModel"].asText())
        assertEquals(listOf("ListFiles"), statePayload["currentSettings"]["enabledTools"].map { it.asText() })
    }

    @Test
    fun `completing onboarding without usable model access returns conflict and does not mark completion`() = testApplication {
        val context = routeTestContext(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = null
                qwenChatKey = null
                aiTunnelKey = null
                anthropicKey = null
                openaiKey = null
            },
        )
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                providerKeyService = context.userProviderKeyService,
                onboardingService = context.onboardingService,
            )
        }

        val response = client.post(BackendHttpRoutes.ONBOARDING_COMPLETE) {
            trustedHeaders("blocked-user")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "locale": "ru-RU",
                  "timeZone": "Europe/Moscow",
                  "enabledTools": ["ListFiles"]
                }
                """.trimIndent()
            )
        }
        val payload = json.readTree(response.bodyAsText())
        val storedSettings = runBlocking { context.userSettingsRepository.get("blocked-user") }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("onboarding_requires_model_access", payload["error"]["code"].asText())
        assertTrue(payload["error"]["message"].asText().contains("model access"))
        assertNull(storedSettings?.onboardingCompletedAt)
    }

    @Test
    fun `completing onboarding rejects unavailable default model for the user`() = testApplication {
        val context = routeTestContext(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
                qwenChatKey = null
                aiTunnelKey = null
                anthropicKey = null
                openaiKey = null
            },
        )
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                providerKeyService = context.userProviderKeyService,
                onboardingService = context.onboardingService,
            )
        }

        val response = client.post(BackendHttpRoutes.ONBOARDING_COMPLETE) {
            trustedHeaders("user-invalid-model")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "defaultModel": "${LLMModel.QwenMax.alias}",
                  "enabledTools": ["ListFiles"]
                }
                """.trimIndent()
            )
        }
        val payload = json.readTree(response.bodyAsText())
        val storedSettings = runBlocking { context.userSettingsRepository.get("user-invalid-model") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", payload["error"]["code"].asText())
        assertTrue(payload["error"]["message"].asText().contains("defaultModel"))
        assertEquals(context.settingsProvider.gigaModel, storedSettings?.defaultModel)
        assertNull(storedSettings?.onboardingCompletedAt)
    }

    @Test
    fun `completing onboarding rejects tools outside the safe backend catalog`() = testApplication {
        val context = routeTestContext(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
            },
        )
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                providerKeyService = context.userProviderKeyService,
                onboardingService = context.onboardingService,
            )
        }

        val response = client.post(BackendHttpRoutes.ONBOARDING_COMPLETE) {
            trustedHeaders("user-invalid-tool")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "enabledTools": ["ListFiles", "OpenBrowser"]
                }
                """.trimIndent()
            )
        }
        val payload = json.readTree(response.bodyAsText())
        val storedSettings = runBlocking { context.userSettingsRepository.get("user-invalid-tool") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", payload["error"]["code"].asText())
        assertTrue(payload["error"]["message"].asText().contains("enabledTools"))
        assertEquals(listOf("ListFiles"), storedSettings?.enabledTools?.toList())
        assertNull(storedSettings?.onboardingCompletedAt)
    }

    @Test
    fun `completing onboarding rejects malformed locale tags`() = testApplication {
        val context = routeTestContext(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
            },
        )
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                providerKeyService = context.userProviderKeyService,
                onboardingService = context.onboardingService,
            )
        }

        val response = client.post(BackendHttpRoutes.ONBOARDING_COMPLETE) {
            trustedHeaders("user-invalid-locale")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "locale": "not-a-locale",
                  "timeZone": "Europe/Moscow",
                  "enabledTools": ["ListFiles"]
                }
                """.trimIndent()
            )
        }
        val payload = json.readTree(response.bodyAsText())
        val storedSettings = runBlocking { context.userSettingsRepository.get("user-invalid-locale") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", payload["error"]["code"].asText())
        assertTrue(payload["error"]["message"].asText().contains("locale"))
        assertNull(storedSettings)
    }

    @Test
    fun `completing onboarding rejects invalid time zones`() = testApplication {
        val context = routeTestContext(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
            },
        )
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                providerKeyService = context.userProviderKeyService,
                onboardingService = context.onboardingService,
            )
        }

        val response = client.post(BackendHttpRoutes.ONBOARDING_COMPLETE) {
            trustedHeaders("user-invalid-zone")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "locale": "ru-RU",
                  "timeZone": "Mars/Phobos",
                  "enabledTools": ["ListFiles"]
                }
                """.trimIndent()
            )
        }
        val payload = json.readTree(response.bodyAsText())
        val storedSettings = runBlocking { context.userSettingsRepository.get("user-invalid-zone") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", payload["error"]["code"].asText())
        assertTrue(payload["error"]["message"].asText().contains("timeZone"))
        assertNull(storedSettings)
    }
}
