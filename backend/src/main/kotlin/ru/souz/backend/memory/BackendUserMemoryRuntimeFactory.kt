package ru.souz.backend.memory

import java.nio.file.Path
import ru.souz.agent.memory.MemoryRuntimeServicesContract
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.memory.MemoryRuntimeServices

class BackendUserMemoryRuntimeFactory(
    private val store: BackendMemoryStore,
    private val settingsProvider: SettingsProvider,
    private val llmApiFactory: suspend (String, String) -> LLMChatAPI,
    private val indexDirResolver: (String) -> Path,
) {
    suspend fun create(
        userId: String,
        requestId: String,
    ): MemoryRuntimeServicesContract =
        MemoryRuntimeServices(
            store = BackendUserMemoryCanonicalStore(userId = userId, store = store),
            embeddingsApi = llmApiFactory(userId, requestId),
            embeddingsFingerprint = { settingsProvider.embeddingsModel.name },
            userScope = MemoryScope(MemoryScopeType.USER, userId),
            vectorIndexDir = indexDirResolver(userId),
        )
}
