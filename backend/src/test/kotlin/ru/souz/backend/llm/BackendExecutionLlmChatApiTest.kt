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
import io.mockk.mockk
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import ru.souz.backend.app.BackendProviderRetryPolicy
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
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.restJsonMapper
import kotlin.time.Duration.Companion.milliseconds

class BackendExecutionLlmChatApiTest {
    @Test
    fun `routes every supported chat provider and caches each adapter`() = runTest {
        val providerCalls = mutableListOf<LlmProvider>()
        val adapterCreations = mutableMapOf<LlmProvider, Int>()
        val providerApis = LlmProvider.entries.associateWith { provider ->
            StubChatApi(
                message = { body ->
                    providerCalls += provider
                    ok(model = body.model)
                }
            )
        }
        facadeFixture(
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
                LLMModel.LocalQwen3_4B_Instruct_2507,
                LLMModel.CodexGpt56Sol,
            )

            models.forEach { model ->
                assertIs<LLMResponse.Chat.Ok>(fixture.api.message(chat(model.alias)))
                assertIs<LLMResponse.Chat.Ok>(fixture.api.message(chat(model.alias)))
            }

            assertEquals(models.map { it.provider }.flatMap { listOf(it, it) }, providerCalls)
            assertEquals(models.associate { it.provider to 1 }, adapterCreations)
            assertEquals(0, fixture.credentialResolver.calls.get())
        }
    }

    @Test
    fun `rejects Giga and unknown chat models before creating an adapter`() = runTest {
        val overrideCalls = AtomicInteger()
        facadeFixture(
            providerApiOverride = {
                overrideCalls.incrementAndGet()
                StubChatApi()
            }
        ).use { fixture ->
            val giga = assertIs<LLMResponse.Chat.Error>(
                fixture.api.message(chat(LLMModel.Max.alias))
            )
            val unknown = assertIs<LLMResponse.Chat.Error>(
                fixture.api.message(chat("not-a-model"))
            )

            assertTrue(giga.message.contains("Unsupported backend chat model"))
            assertTrue(unknown.message.contains("Unsupported backend chat model"))
            assertEquals(0, overrideCalls.get())
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
    fun `retries unary 429 responses and accumulates usage`() = runTest {
        var requests = 0
        val delays = mutableListOf<Long>()
        val providerApi = StubChatApi(
            message = { body ->
                requests += 1
                if (requests == 1) {
                    LLMResponse.Chat.Error(429, "busy retry-after=17")
                } else {
                    ok(body.model, usage(3, 2, 5, 1))
                }
            }
        )
        facadeFixture(
            initialUsage = usage(10, 5, 15, 2),
            retryPolicy = BackendProviderRetryPolicy(
                max429Retries = 1,
                backoffBaseMs = 5,
                backoffMaxMs = 100,
            ),
            delayMillis = { delays += it },
            providerApiOverride = { providerApi },
        ).use { fixture ->
            assertIs<LLMResponse.Chat.Ok>(fixture.api.message(chat(LLMModel.QwenMax.alias)))

            assertEquals(2, requests)
            assertEquals(listOf(17L), delays)
            assertEquals(usage(13, 7, 20, 3), fixture.api.cumulativeUsage())
        }
    }

    @Test
    fun `retries only a first streaming 429 without buffering later items`() = runTest {
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
                            repeat(100) { index ->
                                emit(ok(body.model, usage(index + 1, 0, index + 1, 0)))
                                completedUpstreamEmits += 1
                            }
                        } finally {
                            upstreamCancelled = true
                        }
                    }
                }
            }
        )
        facadeFixture(
            retryPolicy = BackendProviderRetryPolicy(max429Retries = 1, backoffBaseMs = 1, backoffMaxMs = 1),
            delayMillis = {},
            providerApiOverride = { providerApi },
        ).use { fixture ->
            assertIs<LLMResponse.Chat.Ok>(
                fixture.api.messageStream(chat(LLMModel.QwenMax.alias)).first()
            )

            assertEquals(2, streamRequests)
            assertTrue(completedUpstreamEmits <= 1, "The facade consumed the upstream stream ahead of its collector.")
            assertTrue(upstreamCancelled)
            assertEquals(usage(1, 0, 1, 0), fixture.api.cumulativeUsage())
        }
    }

    @Test
    fun `stream accounting uses cumulative usage deltas and propagates cancellation`() = runTest {
        var cancelled = false
        val providerApi = StubChatApi(
            stream = { body ->
                flow {
                    try {
                        emit(ok(body.model, usage(2, 1, 3, 1)))
                        emit(ok(body.model, usage(5, 3, 8, 2)))
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
        )
        facadeFixture(providerApiOverride = { providerApi }).use { fixture ->
            val collected = mutableListOf<LLMResponse.Chat>()
            val failure = assertFailsWith<CancellationException> {
                fixture.api.messageStream(chat(LLMModel.QwenMax.alias)).collect { response ->
                    collected += response
                    if (collected.size == 2) throw CancellationException("stop")
                }
            }

            assertEquals("stop", failure.message)
            assertTrue(cancelled)
            assertEquals(usage(5, 3, 8, 2), fixture.api.cumulativeUsage())
        }
    }
}

private class FacadeFixture(
    val api: BackendExecutionLlmChatApi,
    val credentialResolver: CountingCredentialResolver,
    private val clients: ProviderHttpClients,
) : AutoCloseable {
    override fun close() = clients.close()
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
            respond(
                content = OPENAI_CHAT_RESPONSE,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
    ) {
        providerHttpClientDefaults()
    }

private fun facadeFixture(
    settingsProvider: LlmSettingsStub = LlmSettingsStub(),
    credentialResolver: CountingCredentialResolver = CountingCredentialResolver("test-key"),
    retryPolicy: BackendProviderRetryPolicy = BackendProviderRetryPolicy(max429Retries = 0),
    initialUsage: LLMResponse.Usage = usage(0, 0, 0, 0),
    delayMillis: suspend (Long) -> Unit = {},
    providerApiOverride: ((LlmProvider) -> LLMChatAPI)? = { StubChatApi() },
    client: HttpClient = HttpClient(MockEngine { respondOk() }) {
        providerHttpClientDefaults()
    },
): FacadeFixture {
    val clients = ProviderHttpClients(standard = client, openAi = client)
    val api = BackendExecutionLlmChatApi(
        userId = "user-a",
        settingsProvider = settingsProvider,
        credentialResolver = credentialResolver,
        retryPolicy = retryPolicy,
        httpClients = clients,
        localChatApi = mockk<LocalChatAPI>(relaxed = true),
        codexOAuthService = CodexOAuthService(settingsProvider, client),
        initialUsage = initialUsage,
        delayMillis = delayMillis,
        providerApiOverride = providerApiOverride,
    )
    return FacadeFixture(api, credentialResolver, clients)
}

private class CountingCredentialResolver(
    private val value: String?,
    private val delayMs: Long = 0,
) : ProviderCredentialResolver {
    val calls = AtomicInteger()

    override suspend fun resolve(userId: String, provider: LlmProvider): ResolvedProviderCredential? {
        calls.incrementAndGet()
        if (delayMs > 0) delay(delayMs.milliseconds)
        return value?.let {
            ResolvedProviderCredential(provider, it, CredentialSource.USER_MANAGED)
        }
    }
}

private class StubChatApi(
    private val message: suspend (LLMRequest.Chat) -> LLMResponse.Chat = { ok(it.model) },
    private val stream: suspend (LLMRequest.Chat) -> Flow<LLMResponse.Chat> = { flowOf(ok(it.model)) },
    private val embeddings: suspend (LLMRequest.Embeddings) -> LLMResponse.Embeddings = {
        LLMResponse.Embeddings.Ok(emptyList(), it.model, "list")
    },
) : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = message.invoke(body)

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = stream.invoke(body)

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        embeddings.invoke(body)

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile = error("not used")

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
