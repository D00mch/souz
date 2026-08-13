package ru.souz.backend.llm

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.backend.TestSettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.ProviderSettings

class BackendLlmClientFactoryTest {
    @Test
    fun `user managed credentials override server managed key only for that user`() = runTest {
        val builder = RecordingProviderChatApiBuilder()
        val factory = BackendLlmClientFactory(
            credentialResolver = StaticProviderCredentialResolver(
                serverManaged = mapOf(LlmProvider.OPENAI to "server-openai-key"),
                userManaged = mapOf("user-a" to mapOf(LlmProvider.OPENAI to "user-a-openai")),
            ),
            providerClientFactory = builder,
        )

        val userAApi = factory.create(
            BackendLlmExecutionContext(
                userId = "user-a",
                executionId = "exec-a",
                settingsProvider = TestSettingsProvider().apply { gigaModel = LLMModel.OpenAIGpt52 },
            )
        )
        val userBApi = factory.create(
            BackendLlmExecutionContext(
                userId = "user-b",
                executionId = "exec-b",
                settingsProvider = TestSettingsProvider().apply { gigaModel = LLMModel.OpenAIGpt52 },
            )
        )

        userAApi.message(sampleRequest(LLMModel.OpenAIGpt52.alias))
        userBApi.message(sampleRequest(LLMModel.OpenAIGpt52.alias))

        assertEquals(
            listOf("user-a-openai", "server-openai-key"),
            builder.invocations.map { it.apiKey },
        )
    }

    @Test
    fun `legacy giga aliases route to giga provider`() = runTest {
        val builder = RecordingProviderChatApiBuilder()
        val factory = BackendLlmClientFactory(
            credentialResolver = StaticProviderCredentialResolver(
                serverManaged = mapOf(LlmProvider.GIGA to "server-giga-key"),
                userManaged = emptyMap(),
            ),
            providerClientFactory = builder,
        )

        factory.create(
            BackendLlmExecutionContext(
                userId = "user-a",
                executionId = "exec-a",
                settingsProvider = TestSettingsProvider().apply { gigaModel = LLMModel.OpenAIGpt52 },
            )
        ).message(sampleRequest("GigaChat-Max"))

        assertEquals(LlmProvider.GIGA, builder.invocations.single().provider)
    }

    @Test
    fun `Codex aliases route with the resolved access token`() = runTest {
        val builder = RecordingProviderChatApiBuilder()
        val factory = BackendLlmClientFactory(
            credentialResolver = StaticProviderCredentialResolver(
                serverManaged = mapOf(LlmProvider.CODEX to "server-codex-access-token"),
                userManaged = emptyMap(),
            ),
            providerClientFactory = builder,
        )

        factory.create(
            BackendLlmExecutionContext(
                userId = "user-a",
                executionId = "exec-a",
                settingsProvider = TestSettingsProvider().apply { gigaModel = LLMModel.CodexGpt55 },
            )
        ).message(sampleRequest(LLMModel.CodexGpt55.alias))

        assertEquals(LlmProvider.CODEX, builder.invocations.single().provider)
        assertEquals("server-codex-access-token", builder.invocations.single().apiKey)
    }

    private fun sampleRequest(model: String): LLMRequest.Chat =
        LLMRequest.Chat(
            model = model,
            messages = listOf(LLMRequest.Message(role = LLMMessageRole.user, content = "hello")),
        )
}

private class RecordingProviderChatApiBuilder : ProviderChatApiBuilder {
    val invocations = mutableListOf<ProviderClientInvocation>()

    override fun build(
        provider: LlmProvider,
        settingsProvider: ProviderSettings,
        apiKey: String,
    ): LLMChatAPI {
        return object : LLMChatAPI {
            override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
                invocations += ProviderClientInvocation(
                    provider = provider,
                    apiKey = apiKey,
                )
                return LLMResponse.Chat.Error(499, "recorded only")
            }

            override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = emptyFlow()

            override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
                error("Embeddings are not used in this test.")

            override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
                error("File upload is not used in this test.")

            override suspend fun downloadFile(fileId: String): String? =
                error("File download is not used in this test.")

            override suspend fun balance(): LLMResponse.Balance =
                error("Balance is not used in this test.")
        }
    }
}

private data class ProviderClientInvocation(
    val provider: LlmProvider,
    val apiKey: String,
)

private class StaticProviderCredentialResolver(
    private val serverManaged: Map<LlmProvider, String>,
    private val userManaged: Map<String, Map<LlmProvider, String>>,
) : ProviderCredentialResolver {
    override suspend fun resolve(
        userId: String,
        provider: LlmProvider,
    ): ResolvedProviderCredential? =
        userManaged[userId]?.get(provider)?.let {
            ResolvedProviderCredential(
                provider = provider,
                apiKey = it,
                source = CredentialSource.USER_MANAGED,
            )
        } ?: serverManaged[provider]?.let {
            ResolvedProviderCredential(
                provider = provider,
                apiKey = it,
                source = CredentialSource.SERVER_MANAGED,
            )
        }
}

private class NoopChatApi : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        error("This provider is not used in the test.")

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("This provider is not used in the test.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("This provider is not used in the test.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("This provider is not used in the test.")

    override suspend fun downloadFile(fileId: String): String? =
        error("This provider is not used in the test.")

    override suspend fun balance(): LLMResponse.Balance =
        error("This provider is not used in the test.")
}
