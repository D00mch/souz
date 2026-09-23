package ru.souz.jev

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.ApiClassifier
import ru.souz.llms.runtime.JevClassifier
import ru.souz.llms.runtime.configuredUserMessageClassifier
import ru.souz.runtime.di.runtimeProviderHttpDiModule
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class JevClassifierTest {
    private val request = LLMRequest.Chat(
        model = "conversational-model", provider = LlmProvider.OPENAI,
        messages = listOf(
            LLMRequest.Message(LLMMessageRole.system, "LLM formatting instructions"),
            LLMRequest.Message(LLMMessageRole.user, "History:\nUSER: Prepare for today's meetings"),
            LLMRequest.Message(LLMMessageRole.user, "New message:\nCheck my calendar and email the agenda"),
        ),
    )
    private val categories = mapOf(
        ToolCategory.CALENDAR to "Read and edit calendar events",
        ToolCategory.MAIL to "Read and send email",
        ToolCategory.FILES to "Read and edit files",
    )

    @Test
    fun `classifier uses available categories and conversation content independently of chat model`() = runTest {
        jevHttpClient(MockEngine { httpRequest ->
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            assertEquals("jev-configured", payload["model"].asText())
            assertEquals(request.messages.drop(1).map { it.content }, payload["state"].map { it["content"].asText() })
            assertEquals(listOf("user", "user"), payload["state"].map { it["role"].asText() })
            assertEquals(categories.keys.map { it.name }.toSet(), payload["questions"].fieldNames().asSequence().toSet())
            categories.forEach { (category, description) ->
                kotlin.test.assertContains(payload["questions"][category.name]["instructions"].asText(), description)
            }
            respond(jevResponse(mapOf("CALENDAR" to 0.94, "MAIL" to 0.97, "FILES" to 0.03)))
        }).use { http ->
            val classifier = JevClassifier(JevClient(http, { "test-token" }, { "jev-configured" }))
            val result = classifier.classify(request, categories)
            assertEquals(listOf(ToolCategory.CALENDAR, ToolCategory.MAIL), result.categories)
            assertNull(result.confidence)
        }
    }

    @Test
    fun `threshold is strict including values below the legacy confidence cutoff`() = runTest {
        jevHttpClient(MockEngine {
            respond(jevResponse(mapOf("CALENDAR" to 0.5, "MAIL" to 0.4, "FILES" to 0.0)))
        }).use { http ->
            val client = JevClient(http, { "test-token" })
            val cases = mapOf(
                0.0 to listOf(ToolCategory.CALENDAR, ToolCategory.MAIL),
                0.3 to listOf(ToolCategory.CALENDAR, ToolCategory.MAIL),
                0.4 to listOf(ToolCategory.CALENDAR),
                0.5 to emptyList(),
                1.0 to emptyList(),
            )
            cases.forEach { (threshold, expected) ->
                assertEquals(expected, JevClassifier(client, threshold).classify(request, categories).categories)
            }
        }
    }

    @Test
    fun `empty category catalog performs no request`() = runTest {
        jevHttpClient(MockEngine { error("HTTP must not be called") }).use { http ->
            val result = JevClassifier(JevClient(http, { "test-token" })).classify(request, emptyMap())
            assertEquals(emptyList(), result.categories)
            assertNull(result.confidence)
        }
    }

    @Test
    fun `environment selection preserves defaults and validates only selected Jev configuration`() = runTest {
        jevHttpClient(MockEngine { respond(jevResponse(mapOf("CALENDAR" to 0.4, "MAIL" to 0.3, "FILES" to 0.0))) }).use { http ->
            val missingTokenClient = JevClient(http, { null })
            for (selector in listOf(null, "", "llm", " LLM ")) {
                assertIs<ApiClassifier>(configuredUserMessageClassifier(mockk(), missingTokenClient) {
                    if (it == "SOUZ_CLASSIFIER") selector else "invalid-unused-setting"
                })
            }
            assertFailsWith<IllegalStateException> {
                configuredUserMessageClassifier(mockk(), missingTokenClient) { if (it == "SOUZ_CLASSIFIER") "jev" else null }
            }
            val client = JevClient(http, { "test-token" })
            val environment = mapOf("SOUZ_CLASSIFIER" to " JEV ", "JEV_THRESHOLD" to "0.3")
            val classifier = configuredUserMessageClassifier(mockk(), client, environment::get)
            assertEquals(listOf(ToolCategory.CALENDAR), classifier.classify(request, categories).categories)
            for (threshold in listOf("", "abc", "NaN", "Infinity", "-0.01", "1.01")) {
                assertFailsWith<IllegalArgumentException> {
                    configuredUserMessageClassifier(mockk(), client, (environment + ("JEV_THRESHOLD" to threshold))::get)
                }
            }
            assertFailsWith<IllegalStateException> { configuredUserMessageClassifier(mockk(), client) { "unknown" } }
        }
    }

    @Test
    fun `shared runtime binds one lazy reusable Jev client`() {
        jevHttpClient(MockEngine { error("Binding must not call Jev") }).use { http ->
            val di = DI {
                import(runtimeProviderHttpDiModule(), allowOverride = true)
                bindSingleton(overrides = true) { ProviderHttpClients(http, http) }
            }
            assertSame(di.direct.instance<JevClient>(), di.direct.instance<JevClient>())
        }
    }
}
