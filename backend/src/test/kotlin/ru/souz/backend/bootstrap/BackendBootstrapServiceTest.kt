package ru.souz.backend.bootstrap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.security.RequestIdentity
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.storage.StorageMode
import ru.souz.backend.storage.memory.MemoryUserSettingsRepository
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LocalModelAvailability
import ru.souz.llms.LlmProvider
import ru.souz.tool.ToolCategory

class BackendBootstrapServiceTest {
    @Test
    fun `bootstrap loads user provider keys once and reuses the provider set`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            gigaChatKey = "giga-key"
            qwenChatKey = null
            aiTunnelKey = null
            anthropicKey = null
            openaiKey = null
        }
        val providerKeyRepository = CountingUserProviderKeyRepository(
            keys = listOf(
                UserProviderKey(
                    userId = "user-a",
                    provider = LlmProvider.OPENAI,
                    encryptedApiKey = "enc-openai-user-a",
                    keyHint = "...1234",
                )
            )
        )
        val bootstrapService = BackendBootstrapService(
            settingsProvider = settingsProvider,
            effectiveSettingsResolver = EffectiveSettingsResolver(
                baseSettingsProvider = settingsProvider,
                userSettingsRepository = MemoryUserSettingsRepository(),
                userProviderKeyRepository = providerKeyRepository,
                featureFlags = BackendFeatureFlags(),
                toolCatalog = testToolCatalog(),
                localModelAvailability = unavailableLocalModels(),
            ),
            toolCatalog = testToolCatalog(),
            featureFlags = BackendFeatureFlags(),
            storageMode = StorageMode.MEMORY,
            localModelAvailability = unavailableLocalModels(),
            userProviderKeyRepository = providerKeyRepository,
        )

        val response = bootstrapService.response(RequestIdentity(userId = "user-a"))
        val openAiCapabilities = response.capabilities.models
            .filter { it.provider == LlmProvider.OPENAI.name.lowercase() }

        assertEquals(1, providerKeyRepository.listCalls)
        assertEquals(0, providerKeyRepository.getCalls)
        assertTrue(openAiCapabilities.isNotEmpty())
        assertTrue(openAiCapabilities.all { it.userManagedKey })
    }

    private fun testToolCatalog(): AgentToolCatalog =
        object : AgentToolCatalog {
            override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
                mapOf(
                    ToolCategory.FILES to mapOf("ListFiles" to fakeTool("ListFiles")),
                    ToolCategory.BROWSER to mapOf("OpenBrowser" to fakeTool("OpenBrowser")),
                )
        }

    private fun fakeTool(name: String): LLMToolSetup =
        object : LLMToolSetup {
            override val fn: LLMRequest.Function = LLMRequest.Function(
                name = name,
                description = "test",
                parameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
            )

            override suspend fun invoke(functionCall: LLMResponse.FunctionCall) =
                error("not used in tests")
        }

    private fun unavailableLocalModels(): LocalModelAvailability =
        object : LocalModelAvailability {
            override fun availableGigaModels(): List<LLMModel> = emptyList()

            override fun defaultGigaModel(): LLMModel? = null

            override fun isProviderAvailable(): Boolean = false
        }
}

private class CountingUserProviderKeyRepository(
    private val keys: List<UserProviderKey>,
) : UserProviderKeyRepository {
    var getCalls: Int = 0
        private set

    var listCalls: Int = 0
        private set

    override suspend fun get(userId: String, provider: LlmProvider): UserProviderKey? {
        getCalls += 1
        return keys.firstOrNull { it.userId == userId && it.provider == provider }
    }

    override suspend fun list(userId: String): List<UserProviderKey> {
        listCalls += 1
        return keys.filter { it.userId == userId }
    }

    override suspend fun save(key: UserProviderKey): UserProviderKey =
        error("not used in tests")

    override suspend fun delete(userId: String, provider: LlmProvider): Boolean =
        error("not used in tests")
}
