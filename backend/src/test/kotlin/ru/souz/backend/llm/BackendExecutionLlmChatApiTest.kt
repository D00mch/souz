package ru.souz.backend.llm

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ru.souz.backend.app.BackendLlmLimits
import ru.souz.backend.common.BackendConfigurationException
import ru.souz.backend.settings.service.BackendSettingsProvider
import ru.souz.backend.settings.service.MapBackendServerPreferenceStore
import ru.souz.backend.config.NamedLlmConfig
import ru.souz.backend.config.NamedLlmModel
import ru.souz.backend.config.loadNamedModels
import ru.souz.db.SettingsProvider
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.backend.attachments.AttachmentStorage
import ru.souz.backend.attachments.attachmentTest
import ru.souz.backend.attachments.attachmentPng
import ru.souz.backend.attachments.toContent
import ru.souz.backend.hooks.HookAuth
import ru.souz.backend.hooks.HookConfig
import ru.souz.backend.hooks.HookDefinition
import ru.souz.backend.hooks.HookLlmBudget
import ru.souz.backend.hooks.HookStore
import ru.souz.backend.hooks.LoadedHook
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.llm.CredentialSource.SERVER_MANAGED
import ru.souz.backend.llm.CredentialSource.USER_MANAGED
import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.llm.quota.QuotaExceededException
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.llms.exceedsContextBudget
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.DEFAULT_IMAGE_MODEL
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class BackendExecutionLlmChatApiTest {
    private val pixels = attachmentPng

    @Test
    fun `image projections keep the last five sources and survive storage removal between retries`() = attachmentTest {
        for (streaming in listOf(false, true)) {
            val sources = (0..5).map { storage.save("user-a", "$streaming:$it", "$it.png", pixels.inputStream()) }
            val requests = mutableListOf<LLMRequest.Chat>()
            suspend fun respond(request: LLMRequest.Chat): LLMResponse.Chat {
                requests += request
                assertEquals(5, request.messages.single().attachments!!.size)
                assertFalse(sources.first().id.toString() in request.messages.single().content)
                sources.takeLast(5).forEach { assertTrue(it.id.toString() in request.messages.single().content) }
                if (requests.size == 1) {
                    sources.forEach { objects.delete(it) }
                    return LLMResponse.Chat.Error(429, "retry")
                }
                assertEquals(requests.first(), request)
                return ok(request.model)
            }
            facadeFixture(settingsProvider = LlmSettingsStub().apply { contextSize = 64_000 },
                attachmentStorage = storage, retryPolicy = BackendProviderRetryPolicy(max429Retries = 1),
                providerApiOverride = { StubChatApi(message = ::respond, stream = { flowOf(respond(it)) }) },
            ).use { fixture ->
                val input = chat(LLMModel.OpenAIGpt52.alias).copy(messages = listOf(
                    LLMRequest.Message(LLMMessageRole.user, "inspect", files = sources.map { it.toContent() }),
                ))
                val oversized = input.copy(messages = input.messages + LLMRequest.Message(LLMMessageRole.function, "x".repeat(100_000)))
                assertFalse(oversized.exceedsContextBudget(64_000, includeImageReferences = false))
                assertIs<LLMResponse.Chat.Error>(if (streaming) fixture.api.messageStream(oversized).toList().single()
                    else fixture.api.message(oversized))
                assertTrue(requests.isEmpty())
                val result = if (streaming) fixture.api.messageStream(input).toList().single() else fixture.api.message(input)
                assertIs<LLMResponse.Chat.Ok>(result)
                assertEquals(2, requests.size)
                assertTrue(input.messages.single().attachments.isNullOrEmpty())
            }
        }
    }

    @Test
    fun `unavailable historical images become request-local notes across later text turns`() = attachmentTest {
        val gif = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "gif", it)
        }.toByteArray()
        val unsupported = storage.save("user-a", "gif", "image.gif", gif.inputStream())
        val broken = storage.save("user-a", "broken", "broken.png", pixels.copyOf(33).inputStream())
        val unavailable = listOf("missing", "hash", "size").map { storage.save("user-a", it, "$it.png", pixels.inputStream()) }
        objects.delete(unavailable[0])
        objects.put(unavailable[1], pixels.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        objects.put(unavailable[2], pixels.copyOf(pixels.size - 1))
        val files = (listOf(unsupported, broken) + unavailable).map { it.toContent() }
        val history = listOf(LLMRequest.Message(LLMMessageRole.user, "inspect", files = files))
        facadeFixture(attachmentStorage = storage, providerApiOverride = { StubChatApi(message = { request ->
            assertTrue(request.messages.all { it.attachments.isNullOrEmpty() })
            val content = request.messages.first().content
            assertTrue("unsupported, damaged or too large" in content)
            files.forEach { assertTrue("Image ${it.fileId} unavailable;" in content) }
            ok(request.model)
        }) }).use { fixture ->
            for (messages in listOf(history, history + LLMRequest.Message(LLMMessageRole.user, "continue in text"))) {
                assertIs<LLMResponse.Chat.Ok>(fixture.api.message(chat(LLMModel.OpenAIGpt52.alias).copy(messages = messages)))
            }
            assertEquals("inspect", history.single().content)
            assertEquals(files, history.single().files)
        }
    }

    @Test
    fun `large historical images and near budget questions stay within the dedicated vision limits`() = attachmentTest(maxFileBytes = 20_000_000) {
        val raster = java.awt.image.BufferedImage(2048, 2048, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val random = java.util.Random(1)
        for (y in 0 until raster.height) for (x in 0 until raster.width) raster.setRGB(x, y, random.nextInt())
        val pixels = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(raster, "png", it) }.toByteArray()
        raster.flush()
        assertTrue(pixels.size * 4L / 3 > 10_000_000)
        val file = storage.save("user-a", "noise", "noise.png", pixels.inputStream()).toContent()
        val question = "CURRENT_REQUEST " + "x".repeat(64_000)
        val input = chat("QwenMax").copy(provider = LlmProvider.QWEN, contextSize = 24_000, messages = listOf(
            LLMRequest.Message(LLMMessageRole.user, question, files = listOf(file)),
        ))
        var visionCalls = 0
        facadeFixture(settingsProvider = LlmSettingsStub().apply { contextSize = 24_000 },
            attachmentStorage = storage, visionModel = "AnthropicSonnet45",
            providerApiOverride = { StubChatApi(message = { request ->
                assertFalse(request.exceedsContextBudget(24_000, includeImageReferences = false))
                if (request.provider == LlmProvider.ANTHROPIC) {
                    visionCalls++
                    val message = request.messages.single()
                    assertEquals(1, "CURRENT_REQUEST".toRegex().findAll(message.content).count())
                    assertEquals(1, message.attachments!!.size)
                    assertTrue(message.attachments!!.single().substringAfter(',').length <= 10_000_000)
                } else assertTrue(request.messages.all { it.attachments.isNullOrEmpty() })
                ok(request.model).copy(choices = listOf(LLMResponse.Choice(
                    LLMResponse.Message("Observed noise", LLMMessageRole.assistant, functionsStateId = null), 0, null,
                )))
            }) },
        ).use { fixture ->
            assertIs<LLMResponse.Chat.Ok>(fixture.api.message(input))
            assertIs<LLMResponse.Chat.Ok>(fixture.api.message(input.copy(messages = input.messages +
                LLMRequest.Message(LLMMessageRole.user, "describe again"))))
            assertEquals(2, visionCalls)
            assertEquals(question, input.messages.single().content)
            assertTrue(input.messages.single().attachments.isNullOrEmpty())
        }
    }

    @Test
    fun `named text routes and streams share one visual analysis across retries and fallbacks`() = attachmentTest {
        val files = (1..2).map { storage.save("user-a", "source-$it", "$it.png", pixels.inputStream()).toContent() }
        val input = chat("text").copy(provider = LlmProvider.OPENAI, messages = listOf(
            LLMRequest.Message(LLMMessageRole.user, "compare these screenshots", files = files),
        ))
        var visionCalls = 0
        var mainCalls = 0
        var visionFail = false
        val models = NamedLlmConfig(listOf("text", "vision", "backup").associateWith {
            NamedLlmModel("upstream-$it", "https://$it.test/v1", "$it-key")
        })
        facadeFixture(llmConfig = models, attachmentStorage = storage, visionModel = "vision",
            retryPolicy = BackendProviderRetryPolicy(max429Retries = 1), fallbackModels = listOf("backup"),
            providerApiOverride = { StubChatApi(message = { request ->
                if (request.model == "upstream-vision") {
                    visionCalls++
                    assertEquals(2, request.messages.sumOf { it.attachments.orEmpty().size })
                    if (visionFail) LLMResponse.Chat.Error(503, "no vision") else
                        ok(request.model).copy(choices = listOf(LLMResponse.Choice(
                            LLMResponse.Message("TWO_SCREENS", LLMMessageRole.assistant, functionsStateId = null), 0, null,
                        )))
                } else {
                    assertTrue(request.messages.all { it.attachments.isNullOrEmpty() })
                    assertTrue("TWO_SCREENS" in request.messages.last().content)
                    if (request.model == "upstream-text") LLMResponse.Chat.Error(if (mainCalls++ == 0) 429 else 503, "retry")
                    else ok(request.model)
                }
            }, stream = { request ->
                assertEquals("upstream-text", request.model)
                assertTrue(request.messages.all { it.attachments.isNullOrEmpty() })
                assertTrue("TWO_SCREENS" in request.messages.last().content)
                flowOf(ok(request.model))
            }) },
        ).use { fixture ->
            assertEquals("upstream-backup", assertIs<LLMResponse.Chat.Ok>(fixture.api.message(input)).model)
            assertEquals(1, visionCalls, "Text retries and fallback reuse the analysis")
            fixture.api.messageStream(input).toList()
            assertEquals(2, visionCalls)
            assertEquals(files, input.messages.single().files)
            assertTrue(input.messages.single().attachments.isNullOrEmpty(), "Input projection must not mutate history")
            visionFail = true
            assertEquals(503, assertIs<LLMResponse.Chat.Error>(fixture.api.message(input.copy(model = "vision", provider = null))).status)
            assertEquals(3, visionCalls, "The explicit vision route receives images directly without text fallback")
        }
    }

    @Test
    fun `dedicated vision uses its own model retries and accounting without the text cascade or S3`() = runTest {
        val requests = mutableListOf<LLMRequest.Chat>()
        var failure: Int? = 429
        var cancel = false
        val input = ru.souz.llms.runtime.VisionInput(listOf(ru.souz.llms.runtime.ImageSource.read(pixels)), "read the label")
        facadeFixture(
            settingsProvider = LlmSettingsStub().apply { gigaModel = LLMModel.QwenMax },
            visionModel = "CodexGpt61Sol",
            llmConfig = NamedLlmConfig(reasoningEfforts = mapOf("gpt-6.1-sol" to "low")),
            fallbackModels = listOf("QwenMax"),
            retryPolicy = BackendProviderRetryPolicy(max429Retries = 1),
            providerApiOverride = { provider ->
                assertEquals(LlmProvider.CODEX, provider)
                StubChatApi(message = { request ->
                    requests += request
                    if (cancel) throw CancellationException("stop vision")
                    val status = failure.also { failure = null }
                    if (status != null) LLMResponse.Chat.Error(status, "vision unavailable") else
                        ok(request.model, usage(7, 3, 10, 0)).copy(choices = listOf(LLMResponse.Choice(
                            LLMResponse.Message("Label", LLMMessageRole.assistant, functionsStateId = null), 0, null,
                        )))
                })
            },
        ).use { fixture ->
            fixture.api.visionGateway.analyze(input)
            assertEquals(2, requests.size)
            requests.forEach {
                assertEquals("gpt-6.1-sol", it.model)
                assertEquals("low", it.reasoningEffort)
                assertEquals("read the label", it.messages.single().content)
                assertEquals(1, it.messages.single().attachments!!.size)
                assertTrue(it.functions.isEmpty())
            }
            assertEquals(usage(7, 3, 10, 0), fixture.api.cumulativeUsage())
            failure = 503
            assertFailsWith<IllegalStateException> { fixture.api.visionGateway.analyze(input) }
            assertEquals(3, requests.size, "Vision failure must not call a text-model fallback")
            cancel = true
            assertFailsWith<CancellationException> { fixture.api.visionGateway.analyze(input) }
            assertEquals(4, requests.size)
        }
    }

    @Test
    fun `image selection routes independently of chat through OpenAI named and Codex connections`() = runTest {
        val settings = LlmSettingsStub().apply {
            gigaModel = LLMModel.QwenMax
            codexAccessToken = "codex-test-token"
            codexExpiresAt = Long.MAX_VALUE
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(pixels)
        val requests = mutableListOf<CapturedRequest>()
        val http = HttpClient(MockEngine { request ->
            requests += CapturedRequest(request.url.toString(), request.headers[HttpHeaders.Authorization],
                restJsonMapper.readTree(request.body.toByteArray()))
            if (request.url.host == "chatgpt.com") respond(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"image_generation_call\",\"result\":\"$encoded\"}}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            else respond("""{"data":[{"b64_json":"$encoded"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        val models = NamedLlmConfig(mapOf("named-image" to NamedLlmModel("image-model", "https://images.test/v1", "named-key")))
        facadeFixture(client = http, settingsProvider = settings).use { fixture ->
            val clients = ProviderHttpClients(http, http)
            val selections = listOf(DEFAULT_IMAGE_MODEL, "gpt-image-2", "gpt-image-2.5-flare-2026-09-08", "named-image", "CodexGpt61Sol")
            for (selection in selections) {
                val gateway = ImageModelRoute(selection, models).gateway(
                    settings, clients, CodexOAuthService(settings, http), fixture.api, null,
                )
                kotlin.test.assertContentEquals(pixels, gateway.generate(ru.souz.llms.runtime.ImageGenerationInput("draw")).image.bytes)
            }
            assertEquals(selections.take(3) + listOf("image-model", "gpt-6.1-sol"), requests.map { it.body["model"].asText() })
            assertEquals("https://images.test/v1/images/generations", requests[3].url)
            assertEquals(List(3) { "Bearer test-key" } + listOf("Bearer named-key", "Bearer codex-test-token"),
                requests.map { it.authorization })
            for (request in requests) {
                val options = request.body["tools"]?.get(0) ?: request.body
                assertEquals("1024x1024", options["size"].asText())
                assertEquals("medium", options["quality"].asText())
                assertEquals("png", options["output_format"].asText())
            }
            assertFailsWith<BackendConfigurationException> { ImageModelRoute("unknown", models) }
        }
    }

    @Test
    fun `raw routes preserve IDs across providers retries and stream accounting`() = runTest {
        val requests = mutableListOf<Pair<LlmProvider, LLMRequest.Chat>>()
        val delays = mutableListOf<Long>()
        facadeFixture(
            initialUsage = usage(10, 5, 15, 2),
            retryPolicy = BackendProviderRetryPolicy(max429Retries = 1, backoffBaseMs = 5, backoffMaxMs = 100),
            delayMillis = { delays += it },
            providerApiOverride = { provider ->
                var attempts = 0
                StubChatApi(
                    message = { body ->
                        requests += provider to body
                        if (attempts++ == 0) LLMResponse.Chat.Error(429, "retry-after=17") else ok(body.model, usage(3, 2, 5, 1))
                    },
                    stream = { body ->
                        requests += provider to body
                        flowOf(ok(body.model, usage(2, 0, 2, 0)), ok(body.model, usage(2, 3, 5, 0)))
                    },
                )
            },
        ).use { fixture ->
            listOf(LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.CODEX).forEach { provider ->
                val request = chat("Custom/Deployment").copy(provider = provider)
                assertIs<LLMResponse.Chat.Ok>(fixture.api.message(request))
                assertEquals(2, fixture.api.messageStream(request).toList().size)
                assertEquals(List(3) { provider to request }, requests.takeLast(3))
                val tags = arrayOf("provider", provider.name.lowercase(), "model", "other")
                val registry = fixture.metrics.registry
                assertEquals(1.0, registry.get("souz.llm.requests").tags(*tags, "outcome", "error").counter().count())
                assertEquals(2.0, registry.get("souz.llm.requests").tags(*tags, "outcome", "success").counter().count())
                assertEquals(if (provider == LlmProvider.ANTHROPIC) 6.0 else 5.0,
                    registry.get("souz.llm.tokens").tags(*tags, "direction", "input").counter().count())
                assertEquals(5.0, registry.get("souz.llm.tokens").tags(*tags, "direction", "output").counter().count())
            }
            assertEquals(listOf(17L, 17L, 17L), delays)
            assertEquals(usage(25, 20, 45, 5), fixture.api.cumulativeUsage())
            val rejected = chat("Custom/Deployment").copy(provider = LlmProvider.GIGA)
            assertIs<LLMResponse.Chat.Error>(fixture.api.message(rejected))
            assertIs<LLMResponse.Chat.Error>(fixture.api.messageStream(rejected).toList().single())
            assertEquals(9, requests.size)
        }
    }

    @Test
    fun `unary chat falls back once per model on provider failures only`() = runTest {
        val primary = LLMModel.OpenAIGpt52.alias
        val fallback = LLMModel.QwenMax.alias
        val switched = listOf(primary, fallback)
        val kept = listOf(primary)
        for ((status, attempts) in listOf(
            -1 to switched, 500 to switched, 401 to switched, 402 to switched, 403 to switched, 408 to switched,
            429 to listOf(primary, primary, fallback), 400 to kept, 404 to kept, 413 to kept, 422 to kept,
        )) {
            val calls = mutableListOf<String>()
            val failure = LLMResponse.Chat.Error(status, "down")
            facadeFixture(
                retryPolicy = BackendProviderRetryPolicy(max429Retries = 1),
                fallbackModels = listOf(primary, fallback),
                providerApiOverride = { StubChatApi(message = { calls += it.model; if (it.model == primary) failure else ok(it.model) }) },
            ).use { fixture ->
                assertEquals(if (attempts == kept) failure else ok(fallback), fixture.api.message(chat(primary)))
                assertEquals(attempts, calls, "status $status")
            }
        }

        val calls = mutableListOf<String>()
        var cancel = false
        facadeFixture(
            fallbackModels = listOf(fallback),
            providerApiOverride = {
                StubChatApi(
                    message = { calls += it.model; if (cancel) throw CancellationException("stop") else LLMResponse.Chat.Error(500, it.model) },
                    stream = { calls += it.model; flowOf(LLMResponse.Chat.Error(500, it.model)) },
                )
            },
        ).use { fixture ->
            assertEquals(LLMResponse.Chat.Error(500, fallback), fixture.api.message(chat(primary)))
            assertEquals(listOf(LLMResponse.Chat.Error(500, primary)), fixture.api.messageStream(chat(primary)).toList())
            cancel = true
            assertFailsWith<CancellationException> { fixture.api.message(chat(primary)) }
            assertEquals(listOf(primary, fallback, primary, primary), calls)
        }

        // Named connections with one endpoint and upstream ID still differ by key.
        val namedCalls = mutableListOf<String>()
        facadeFixture(
            llmConfig = NamedLlmConfig(models = listOf("bad", "good").associateWith { NamedLlmModel("shared", "https://same.test/v1", "$it-key") }),
            fallbackModels = listOf("good"),
            providerApiOverride = { StubChatApi(message = { namedCalls += it.model; if (namedCalls.size == 1) LLMResponse.Chat.Error(500, "down") else ok(it.model) }) },
        ).use { assertEquals(ok("shared"), it.api.message(chat("bad"))) }
    }

    @Test
    fun `failure memory skips a model after three failures per credential owner until one trial`() = runTest {
        val time = TestTimeSource()
        val memory = ModelFailureMemory(timeSource = time)
        var primaryDown = true
        val primary = LLMModel.OpenAIGpt52.alias
        val fallback = "Fallback/Deployment"
        suspend fun attempts(userId: String, source: CredentialSource): List<String> {
            val calls = mutableListOf<String>()
            facadeFixture(
                userId = userId,
                credentialResolver = CountingCredentialResolver("key", source = source),
                fallbackModels = listOf(fallback),
                failureMemory = memory,
                providerApiOverride = {
                    StubChatApi(message = { calls += it.model; if (primaryDown && it.model == primary) LLMResponse.Chat.Error(500, "down") else ok(it.model) })
                },
            ).use { it.api.message(chat(primary)) }
            return calls
        }
        val both = listOf(primary, fallback)
        repeat(3) { assertEquals(both, attempts("user-a", USER_MANAGED)) }
        assertEquals(listOf(fallback), attempts("user-a", USER_MANAGED))
        assertEquals(both, attempts("user-b", USER_MANAGED))
        repeat(3) { assertEquals(both, attempts("user-b", SERVER_MANAGED)) }
        assertEquals(listOf(fallback), attempts("user-c", SERVER_MANAGED))
        time += 5.minutes
        assertEquals(both, attempts("user-c", SERVER_MANAGED))
        assertEquals(listOf(fallback), attempts("user-c", SERVER_MANAGED))
        time += 5.minutes
        primaryDown = false
        repeat(2) { assertEquals(listOf(primary), attempts("user-c", SERVER_MANAGED)) }

        // Concurrent calls after a cooldown: only the first one gets the trial.
        repeat(3) { memory.record("model", failed = true) }
        time += 5.minutes
        assertEquals(listOf(true, false), List(2) { memory.shouldTry("model") })
    }

    @Test
    fun `raw OpenAI deployment uses execution credentials instead of default model`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val credentials = CountingCredentialResolver("execution-key")
        facadeFixture(
            credentialResolver = credentials,
            providerApiOverride = null,
            client = recordingClient(requests),
        ).use { fixture ->
            fixture.api.message(chat("My-Deployment/v2").copy(provider = LlmProvider.OPENAI))
            assertEquals("My-Deployment/v2", requests.single().body["model"].asText())
            assertEquals("Bearer execution-key", requests.single().authorization)
            assertEquals(1, credentials.calls.get())
        }
    }

    @Test
    fun `named routes isolate connections and parameters in both modes and summaries`() = withNamedLlmConfig(
        NAMED_LLM_CONFIG
            .replace("\"connection\":\"experiment\"", """"connection":"experiment","parameters":{"thinking":{"type":"disabled"},"reasoning_effort":"low"}""")
            .replace("\"connection\":\"router\"", """"connection":"router","parameters":{"thinking":{"type":"enabled"},"reasoning_effort":"low"}"""),
        environment = mapOf("OPENAI_SUMMARIZATION_PARAMETERS" to """{"thinking":{"type":"disabled"},"top_p":0.9}"""),
    ) { source ->
        runTest {
            val requests = mutableListOf<CapturedRequest>()
            val config = source.loadNamedModels()
            val settings = BackendSettingsProvider(MapBackendServerPreferenceStore(), source, config).apply {
                openaiModel = "shared-model"
                openaiBaseUrl = "https://legacy.test/v1"
            }
            facadeFixture(settingsProvider = settings, llmConfig = config, providerApiOverride = null, client = recordingClient(requests)).use { fixture ->
                for ((request, expected) in listOf(
                    chat("test-chat").copy(provider = LlmProvider.OPENAI) to "experiment",
                    chat("tv-agent").copy(provider = LlmProvider.OPENAI) to "router",
                    chat("shared-model").copy(provider = LlmProvider.OPENAI) to "legacy",
                    chat(LLMModel.OpenAICompatibleCustom.alias) to "legacy",
                    chat("test-chat") to "experiment",
                    chat("tv-agent").copy(reasoningEffort = "high") to "router",
                    chat("test-chat").copy(isSummarization = true) to "router",
                )) {
                    val before = requests.size
                    assertIs<LLMResponse.Chat.Ok>(fixture.api.message(request))
                    if (!request.isSummarization) {
                        assertTrue(fixture.api.messageStream(request).toList().any { it is LLMResponse.Chat.Ok })
                    }
                    requests.drop(before).forEach {
                        assertEquals("https://$expected.test/v1/chat/completions", it.url)
                        assertEquals("Bearer " + if (expected == "legacy") "test-key" else "$expected-secret", it.authorization)
                        assertEquals("shared-model", it.body["model"].asText())
                        val thinking = when (expected) { "experiment" -> "disabled"; "router" -> "enabled"; else -> null }
                        assertEquals(thinking, it.body.path("thinking").path("type").textValue())
                        assertEquals(request.reasoningEffort ?: "low".takeIf { expected != "legacy" }, it.body.path("reasoning_effort").textValue())
                        assertEquals(request.maxTokens, it.body["max_completion_tokens"].asInt())
                        if (request.isSummarization) assertEquals(0.9, it.body["top_p"].asDouble())
                        assertTrue(!it.body.has("provider"))
                    }
                }
                assertEquals(1, fixture.credentialResolver.calls.get())
            }
        }
    }

    @Test
    fun `custom selectors use host model resolution while raw IDs are never rewritten`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val settings = LlmSettingsStub().apply { gigaModel = LLMModel.OpenAIGpt52 }
        facadeFixture(settingsProvider = settings, providerApiOverride = null, client = recordingClient(requests)).use { fixture ->
            val request = chat(LLMModel.OpenAICompatibleCustom.alias)
            fixture.api.message(request)
            fixture.api.message(request.copy(provider = LlmProvider.OPENAI))
            settings.openaiModel = " Deployment/ID "
            fixture.api.message(request)
            assertEquals(
                listOf(request.model, request.model, "Deployment/ID"),
                requests.map { it.body["model"].asText() },
            )
        }
    }

    @Test
    fun `dedicated summarization resolves its model endpoint and credentials before dispatch`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val settings = LlmSettingsStub().apply {
            openaiSummarizationModel = "Summary/Deployment"
            openaiSummarizationBaseUrl = "https://summary.test/v1"
            openaiSummarizationApiKey = "summary-key"
            openaiSummarizationParameters = """{"model":"ignored","max_tokens":512,"max_completion_tokens":512}"""
        }
        facadeFixture(settingsProvider = settings, providerApiOverride = null, client = recordingClient(requests)).use { fixture ->
            val request = chat("Parent/Deployment").copy(provider = LlmProvider.ANTHROPIC, isSummarization = true, maxTokens = 1024)
            assertIs<LLMResponse.Chat.Ok>(fixture.api.message(request))
            val outbound = requests.single()
            assertEquals("https://summary.test/v1/chat/completions", outbound.url)
            assertEquals("Bearer summary-key", outbound.authorization)
            assertEquals("Summary/Deployment", outbound.body["model"].asText())
            assertEquals(1024, outbound.body["max_completion_tokens"].asInt())
            assertFalse(outbound.body.has("max_tokens"))
            assertEquals(0, fixture.credentialResolver.calls.get())
            assertEquals(usage(1, 1, 2, 0), fixture.api.cumulativeUsage())
            assertEquals(1.0, fixture.metrics.registry.get("souz.llm.requests")
                .tags("provider", "openai", "model", "other", "outcome", "success").counter().count())
        }
    }

    @Test
    fun `routes every supported chat provider and caches each adapter`() = runTest {
        val providerCalls = mutableListOf<LlmProvider>()
        val efforts = mutableListOf<String?>()
        val defaults = mapOf(LLMModel.OpenAIGpt52.alias to "low", LLMModel.CodexGpt56Sol.alias to "minimal")
        val adapterCreations = mutableMapOf<LlmProvider, Int>()
        val providerApis = LlmProvider.entries.associateWith { provider ->
            StubChatApi(
                message = { body ->
                    providerCalls += provider
                    efforts += body.reasoningEffort
                    ok(model = body.model)
                },
                stream = { body ->
                    providerCalls += provider
                    efforts += body.reasoningEffort
                    flowOf(ok(model = body.model))
                },
            )
        }
        facadeFixture(
            llmConfig = NamedLlmConfig(reasoningEfforts = defaults),
            providerApiOverride = { provider ->
                adapterCreations[provider] = adapterCreations.getOrDefault(provider, 0) + 1
                providerApis.getValue(provider)
            }
        ).use { fixture ->
            val models = listOf(
                LLMModel.QwenMax,
                LLMModel.AiTunnelGpt54Mini,
                LLMModel.AnthropicSonnet45,
                LLMModel.OpenAIGpt52,
                LLMModel.CodexGpt56Sol,
            )

            models.forEach { model ->
                assertIs<LLMResponse.Chat.Ok>(fixture.api.message(chat(model.alias)))
                assertIs<LLMResponse.Chat.Ok>(fixture.api.messageStream(chat(model.name)).toList().single()).also {
                    assertEquals(model.alias, it.model)
                }
            }

            assertEquals(models.map { it.provider }.flatMap { listOf(it, it) }, providerCalls)
            assertEquals(models.flatMap { listOf(defaults[it.alias], defaults[it.alias]) }, efforts)
            for (model in defaults.keys) {
                fixture.api.message(chat(model).copy(reasoningEffort = "high"))
                fixture.api.messageStream(chat(model).copy(reasoningEffort = "high")).toList()
                assertEquals(listOf("high", "high"), efforts.takeLast(2))
            }
            fixture.api.message(chat("raw-model").copy(provider = LlmProvider.OPENAI))
            assertEquals(null, efforts.last())
            assertEquals(models.associate { it.provider to 1 }, adapterCreations)
            assertEquals(0, fixture.credentialResolver.calls.get())
        }
    }

    @Test
    fun `rejects unavailable chat routes with the same unary and streaming errors before creating an adapter`() = runTest {
        val overrideCalls = AtomicInteger()
        facadeFixture(
            providerApiOverride = {
                overrideCalls.incrementAndGet()
                StubChatApi()
            }
        ).use { fixture ->
            listOf(
                chat(LLMModel.Max.alias) to "provider GIGA is unsupported",
                chat("Custom/Deployment").copy(provider = LlmProvider.GIGA) to "provider GIGA is unsupported",
                chat(" not-a-model ") to "not-a-model",
                chat("   ") to "",
            ).forEach { (request, description) ->
                val expected = LLMResponse.Chat.Error(-1, "Unsupported backend chat model: $description.")
                assertEquals(expected, fixture.api.message(request))
                assertEquals(listOf(expected), fixture.api.messageStream(request).toList())
            }
            assertEquals(0, overrideCalls.get())
            assertEquals(0, fixture.credentialResolver.calls.get())
        }
    }

    @Test
    fun `resolves one credential once across concurrent callers`() = runTest {
        val resolver = CountingCredentialResolver(value = "openai-key", delayMs = 10)
        facadeFixture(credentialResolver = resolver).use { fixture ->
            val credentials = coroutineScope {
                List(2) {
                    async { fixture.api.credentialFor(LlmProvider.OPENAI) }
                }.awaitAll()
            }

            assertEquals(listOf("openai-key", "openai-key"), credentials)
            assertEquals(1, resolver.calls.get())
        }
    }

    @Test
    fun `caches a missing credential resolution outcome`() = runTest {
        val resolver = CountingCredentialResolver(value = null)
        facadeFixture(credentialResolver = resolver).use { fixture ->
            val failures = coroutineScope {
                List(2) {
                    async {
                        runCatching { fixture.api.credentialFor(LlmProvider.OPENAI) }
                            .exceptionOrNull()
                    }
                }.awaitAll()
            }

            assertTrue(failures.all { it is IllegalStateException })
            assertEquals(1, resolver.calls.get())
        }
    }

    @Test
    fun `routes default and explicit embeddings while rejecting unsupported providers`() = runTest {
        val embeddingRequests = mutableListOf<Pair<LlmProvider, String>>()
        val providerApis = LlmProvider.entries.associateWith { provider ->
            StubChatApi(
                embeddings = { body ->
                    embeddingRequests += provider to body.model
                    LLMResponse.Embeddings.Ok(emptyList(), body.model, "list")
                }
            )
        }
        val settings = LlmSettingsStub().apply {
            embeddingsModel = EmbeddingsModel.OpenAITextEmbedding3Small
        }
        facadeFixture(
            settingsProvider = settings,
            providerApiOverride = providerApis::getValue,
        ).use { fixture ->
            assertIs<LLMResponse.Embeddings.Ok>(
                fixture.api.embeddings(embeddings("  embeddings  "))
            )
            assertIs<LLMResponse.Embeddings.Ok>(
                fixture.api.embeddings(embeddings(EmbeddingsModel.QwenEmbeddings.alias))
            )
            assertEquals(
                listOf(
                    LlmProvider.OPENAI to EmbeddingsModel.OpenAITextEmbedding3Small.alias,
                    LlmProvider.QWEN to EmbeddingsModel.QwenEmbeddings.alias,
                ),
                embeddingRequests,
            )

            settings.embeddingsModel = EmbeddingsModel.GigaEmbeddings
            assertIs<LLMResponse.Embeddings.Error>(fixture.api.embeddings(embeddings("Embeddings")))
        }
    }

    @Test
    fun `hook provider calls hold capacity without charging rejected calls and release it on cancellation`() = runTest {
        PostgresDataSourceFactory.create(postgresAppConfig(newPostgresSchema("provider_permit")).postgres).use { db ->
            PostgresUserRepository(db).ensureUser("user-a")
            for (kind in listOf("chat", "stream", "embeddings", "upload")) {
                val store = HookStore(db, HookConfig(llmCallsPerEvent = 2))
                val hook = LoadedHook(HookDefinition(1, "permit-test", "user-a", auth = HookAuth("bearer", "a".repeat(64)), prompt = "Test"))
                val receiptId = store.accept(hook, "{}", null).receiptId
                store.update(receiptId, "running")
                val receipt = requireNotNull(store.find("user-a", receiptId))
                val quotas = ExecutionQuotaManager(BackendLlmLimits(globalProviderConcurrency = 1))
                val budget = HookLlmBudget(receipt, store, quotas)
                for (limit in listOf(512, 8192)) {
                    val summaryBudget = HookLlmBudget(receipt, store, quotas)
                    assertEquals(minOf(limit, 4096), summaryBudget.limitRequest(
                        chat("summary").copy(isSummarization = true, maxTokens = limit),
                    ).maxTokens)
                }
                val model = if (kind == "upload") LLMModel.AnthropicSonnet45 else LLMModel.OpenAIGpt52
                val entered = CompletableDeferred<Unit>()
                suspend fun holdPermit() {
                    if (entered.complete(Unit)) awaitCancellation()
                }
                val provider = StubChatApi(
                    message = { body -> holdPermit(); ok(body.model, usage(1, 1, 2, 0)) },
                    stream = { body -> flow { emit(ok(body.model, usage(1, 1, 2, 0))); holdPermit() } },
                    embeddings = { body -> holdPermit(); LLMResponse.Embeddings.Ok(emptyList(), body.model, "list") },
                    upload = { file ->
                        holdPermit()
                        LLMResponse.UploadFile(0, 0, file.name, "uploaded-image", "file", "vision", "private")
                    },
                )
                facadeFixture(
                    settingsProvider = LlmSettingsStub().apply { gigaModel = model },
                    providerApiOverride = { assertEquals(model.provider, it); provider },
                    hookBudget = budget,
                ).use { fixture ->
                    suspend fun call() {
                        val request = chat("test-model").copy(provider = LlmProvider.OPENAI)
                        when (kind) {
                            "chat" -> assertIs<LLMResponse.Chat.Ok>(fixture.api.message(request))
                            "stream" -> assertIs<LLMResponse.Chat.Ok>(fixture.api.messageStream(request).toList().single())
                            "embeddings" -> assertIs<LLMResponse.Embeddings.Ok>(fixture.api.embeddings(embeddings(EmbeddingsModel.OpenAITextEmbedding3Small.name)))
                            "upload" -> assertEquals("uploaded-image", fixture.api.uploadFile(File("image.png")).id)
                        }
                    }
                    val running = async { call() }
                    try {
                        entered.await()
                        assertFailsWith<QuotaExceededException> { quotas.withProviderPermit(model.provider) {} }
                        assertEquals("global_provider_concurrency_exceeded", assertFailsWith<QuotaExceededException> {
                            call()
                        }.code)
                        assertEquals(1, store.find("user-a", receiptId)?.llmCalls)
                    } finally {
                        running.cancelAndJoin()
                    }
                    call()
                    assertEquals(2, store.find("user-a", receiptId)?.llmCalls)
                    assertEquals("hook_execution_llm_limit", assertFailsWith<BackendV1Exception> { call() }.code)
                }
            }
        }
    }

    @Test
    fun `stream retries preserve cumulative usage and propagate cancellation without buffering`() = runTest {
        var streamRequests = 0
        var completedUpstreamEmits = 0
        var upstreamCancelled = false
        val providerApi = StubChatApi(
            stream = { body ->
                streamRequests += 1
                if (streamRequests == 1) {
                    flowOf(LLMResponse.Chat.Error(429, "retry-after=1"))
                } else {
                    flow {
                        try {
                            for (tokens in listOf(
                                usage(2, 1, 3, 1), usage(0, 0, 0, 0), usage(1, 1, 2, 0),
                                usage(5, 3, 8, 2), usage(5, 3, 8, 2),
                            )) {
                                emit(ok(body.model, tokens))
                                completedUpstreamEmits += 1
                            }
                            awaitCancellation()
                        } finally {
                            upstreamCancelled = true
                        }
                    }
                }
            }
        )
        facadeFixture(
            retryPolicy = BackendProviderRetryPolicy(max429Retries = 1, backoffBaseMs = 1, backoffMaxMs = 1),
            providerApiOverride = { providerApi },
        ).use { fixture ->
            var collected = 0
            val failure = assertFailsWith<CancellationException> {
                fixture.api.messageStream(chat(LLMModel.QwenMax.alias)).collect { response ->
                    assertIs<LLMResponse.Chat.Ok>(response)
                    if (++collected == 5) throw CancellationException("stop")
                }
            }
            assertEquals(2, streamRequests)
            assertEquals(4, completedUpstreamEmits, "The facade consumed the upstream stream ahead of its collector.")
            assertTrue(upstreamCancelled)
            assertEquals("stop", failure.message)
            assertEquals(usage(5, 3, 8, 2), fixture.api.cumulativeUsage())
            val registry = fixture.metrics.registry
            val tags = arrayOf("provider", "qwen", "model", LLMModel.QwenMax.alias)
            assertEquals(1.0, registry.get("souz.llm.requests").tags(*tags, "outcome", "error").counter().count())
            assertEquals(1.0, registry.get("souz.llm.requests").tags(*tags, "outcome", "cancelled").counter().count())
            assertEquals(5.0, registry.get("souz.llm.tokens").tags(*tags, "direction", "input").counter().count())
        }
    }
}

private class FacadeFixture(
    val api: BackendExecutionLlmChatApi,
    val credentialResolver: CountingCredentialResolver,
    private val clients: ProviderHttpClients,
    val metrics: BackendMetrics,
) : AutoCloseable {
    override fun close() {
        clients.close()
        metrics.close()
    }
}

private data class CapturedRequest(
    val url: String,
    val authorization: String?,
    val body: JsonNode,
)

private fun recordingClient(requests: MutableList<CapturedRequest>): HttpClient =
    HttpClient(
        MockEngine { request ->
            requests += CapturedRequest(
                url = request.url.toString(),
                authorization = request.headers[HttpHeaders.Authorization],
                body = restJsonMapper.readTree(request.body.toByteArray()),
            )
            val stream = requests.last().body["stream"]?.asBoolean() == true
            respond(
                content = if (stream) "data: $OPENAI_CHAT_RESPONSE\n\ndata: [DONE]\n\n" else OPENAI_CHAT_RESPONSE,
                headers = headersOf(HttpHeaders.ContentType, if (stream) "text/event-stream" else ContentType.Application.Json.toString()),
            )
        }
    ) {
        providerHttpClientDefaults()
    }

private fun facadeFixture(
    userId: String = "user-a",
    llmConfig: NamedLlmConfig = NamedLlmConfig(),
    settingsProvider: SettingsProvider = LlmSettingsStub(),
    credentialResolver: CountingCredentialResolver = CountingCredentialResolver("test-key"),
    retryPolicy: BackendProviderRetryPolicy = BackendProviderRetryPolicy(max429Retries = 0),
    initialUsage: LLMResponse.Usage = usage(0, 0, 0, 0),
    delayMillis: suspend (Long) -> Unit = {},
    providerApiOverride: ((LlmProvider) -> LLMChatAPI)? = { StubChatApi() },
    hookBudget: HookLlmBudget? = null,
    client: HttpClient = HttpClient(MockEngine { respondOk() }) {
        providerHttpClientDefaults()
    },
    fallbackModels: List<String> = emptyList(),
    failureMemory: ModelFailureMemory = ModelFailureMemory(),
    visionModel: String? = null,
    attachmentStorage: AttachmentStorage? = null,
): FacadeFixture {
    val clients = ProviderHttpClients(standard = client, openAi = client)
    val metrics = BackendMetrics()
    val api = BackendExecutionLlmChatApi(
        llmConfig = llmConfig,
        userId = userId,
        settingsProvider = settingsProvider,
        credentialResolver = credentialResolver,
        retryPolicy = retryPolicy,
        httpClients = clients,
        codexOAuthService = CodexOAuthService(settingsProvider, client),
        initialUsage = initialUsage,
        delayMillis = delayMillis,
        providerApiOverride = providerApiOverride,
        hookBudget = hookBudget,
        metrics = metrics,
        fallbackModels = fallbackModels,
        failureMemory = failureMemory,
        visionModel = visionModel,
        attachmentStorage = attachmentStorage,
    )
    return FacadeFixture(api, credentialResolver, clients, metrics)
}

private class CountingCredentialResolver(
    private val value: String?,
    private val delayMs: Long = 0,
    private val source: CredentialSource = CredentialSource.USER_MANAGED,
) : ProviderCredentialResolver {
    val calls = AtomicInteger()

    override suspend fun resolve(userId: String, provider: LlmProvider): ResolvedProviderCredential? {
        calls.incrementAndGet()
        if (delayMs > 0) delay(delayMs.milliseconds)
        return value?.let {
            ResolvedProviderCredential(provider, it, source)
        }
    }
}

private class StubChatApi(
    private val message: suspend (LLMRequest.Chat) -> LLMResponse.Chat = { ok(it.model) },
    private val stream: suspend (LLMRequest.Chat) -> Flow<LLMResponse.Chat> = { flowOf(ok(it.model)) },
    private val embeddings: suspend (LLMRequest.Embeddings) -> LLMResponse.Embeddings = {
        LLMResponse.Embeddings.Ok(emptyList(), it.model, "list")
    },
    private val upload: suspend (File) -> LLMResponse.UploadFile = { error("not used") },
) : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = message.invoke(body)

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = stream.invoke(body)

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        embeddings.invoke(body)

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile = upload(file)

    override suspend fun downloadFile(fileId: String): String = error("not used")

    override suspend fun balance(): LLMResponse.Balance = error("not used")
}

private fun chat(model: String): LLMRequest.Chat = LLMRequest.Chat(
    model = model,
    messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
)

private fun embeddings(model: String): LLMRequest.Embeddings =
    LLMRequest.Embeddings(model = model, input = listOf("hello"))

private fun ok(
    model: String,
    usage: LLMResponse.Usage = usage(0, 0, 0, 0),
): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
    choices = emptyList(),
    created = 0,
    model = model,
    usage = usage,
)

private fun usage(
    prompt: Int,
    completion: Int,
    total: Int,
    precached: Int,
): LLMResponse.Usage = LLMResponse.Usage(prompt, completion, total, precached)

private const val OPENAI_CHAT_RESPONSE =
    """{"choices":[],"created":0,"model":"provider-summary-model",""" +
        """"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
