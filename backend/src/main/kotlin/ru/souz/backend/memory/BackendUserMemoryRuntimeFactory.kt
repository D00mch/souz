package ru.souz.backend.memory

import java.nio.file.Path
import java.time.Instant
import ru.souz.agent.memory.MemoryRuntimeServicesContract
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.agent.memory.NoOpMemoryRuntimeServices
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.memory.MemoryRuntimeServices

class BackendUserMemoryRuntimeFactory(
    private val store: BackendMemoryStore,
    private val settingsProvider: SettingsProvider,
    private val llmApiFactory: suspend (String, String) -> LLMChatAPI,
    private val indexDirResolver: (String) -> Path,
    private val memoryEnabledResolver: suspend (String) -> Boolean = { settingsProvider.memoryEnabled },
    private val chatModelAliasResolver: suspend (String) -> String = { settingsProvider.gigaModel.alias },
) {
    suspend fun create(
        userId: String,
        requestId: String,
    ): MemoryRuntimeServicesContract {
        val chatModelAlias = chatModelAliasResolver(userId)
        val delegate = MemoryRuntimeServices(
            store = BackendUserMemoryCanonicalStore(userId = userId, store = store),
            embeddingsApi = llmApiFactory(userId, requestId),
            embeddingsFingerprint = { settingsProvider.embeddingsModel.name },
            chatModelAlias = { chatModelAlias },
            userScope = MemoryScope(MemoryScopeType.USER, userId),
            vectorIndexDir = indexDirResolver(userId),
        )
        return object : MemoryRuntimeServicesContract {
            suspend fun automaticMemoryRuntime(): MemoryRuntimeServicesContract =
                if (memoryEnabledResolver(userId)) delegate else NoOpMemoryRuntimeServices

            override suspend fun write(input: ru.souz.agent.memory.MemoryWriteInput) =
                automaticMemoryRuntime().write(input)

            override suspend fun inject(request: ru.souz.agent.memory.MemoryInjectionRequest) =
                automaticMemoryRuntime().inject(request)

            override suspend fun graphSnapshot(scope: MemoryScope) = delegate.graphSnapshot(scope)

            override suspend fun forgetFact(factId: String, at: Instant) = delegate.forgetFact(factId, at)

            override suspend fun invalidateFact(factId: String, at: Instant) = delegate.invalidateFact(factId, at)

            override suspend fun rebuildProjection() = delegate.rebuildProjection()
        }
    }
}
