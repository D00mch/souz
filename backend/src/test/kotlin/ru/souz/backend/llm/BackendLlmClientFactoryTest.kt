package ru.souz.backend.llm

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import ru.souz.backend.TestSettingsProvider
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider

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
            localChatApi = NoopChatApi(),
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

        assertEquals("user-a", builder.invocations[0].userId)
        assertEquals("user-a-openai", builder.invocations[0].apiKey)
        assertEquals("user-b", builder.invocations[1].userId)
        assertEquals("server-openai-key", builder.invocations[1].apiKey)
    }

    @Test
    fun `shared transport is reused while execution scoped credentials stay isolated`() = runTest {
        val builder = RecordingProviderChatApiBuilder()
        val factory = BackendLlmClientFactory(
            credentialResolver = StaticProviderCredentialResolver(
                serverManaged = mapOf(LlmProvider.OPENAI to "server-openai-key"),
                userManaged = mapOf("user-a" to mapOf(LlmProvider.OPENAI to "user-a-openai")),
            ),
            providerClientFactory = builder,
            localChatApi = NoopChatApi(),
        )

        factory.create(
            BackendLlmExecutionContext(
                userId = "user-a",
                executionId = "exec-a",
                settingsProvider = TestSettingsProvider().apply { gigaModel = LLMModel.OpenAIGpt52 },
            )
        ).message(sampleRequest(LLMModel.OpenAIGpt52.alias))
        factory.create(
            BackendLlmExecutionContext(
                userId = "user-b",
                executionId = "exec-b",
                settingsProvider = TestSettingsProvider().apply { gigaModel = LLMModel.OpenAIGpt52 },
            )
        ).message(sampleRequest(LLMModel.OpenAIGpt52.alias))

        assertEquals(2, builder.invocations.size)
        assertEquals(builder.invocations[0].transportId, builder.invocations[1].transportId)
        assertNotEquals(builder.invocations[0].apiKey, builder.invocations[1].apiKey)
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
        settingsProvider: SettingsProvider,
        sharedTransport: SharedProviderTransport,
        executionContext: BackendLlmExecutionContext,
    ): LLMChatAPI {
        return object : LLMChatAPI {
            override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
                invocations += ProviderClientInvocation(
                    userId = executionContext.userId,
                    provider = provider,
                    apiKey = settingsProvider.openaiKey.orEmpty(),
                    transportId = sharedTransport.id,
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
    val userId: String,
    val provider: LlmProvider,
    val apiKey: String,
    val transportId: String,
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
