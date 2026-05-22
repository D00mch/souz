package ru.souz.di

import java.nio.file.Files
import org.slf4j.LoggerFactory
import ru.souz.agent.memory.MemoryGraphQueryService
import ru.souz.agent.memory.MemoryMaintenanceService
import ru.souz.agent.memory.MemoryRetrievalService
import ru.souz.agent.memory.MemoryRuntimeServicesContract
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.agent.memory.MemoryWriteService
import ru.souz.agent.memory.NoOpMemoryRuntimeServices
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LocalUserId
import ru.souz.memory.MemoryRuntimeServices
import ru.souz.memory.SqliteMemoryStore
import ru.souz.paths.DefaultSouzPaths
import ru.souz.paths.SouzPaths

class DesktopMemoryRuntimeFactory(
    private val paths: SouzPaths = DefaultSouzPaths(),
    private val settingsProvider: SettingsProvider? = null,
    private val embeddingsApi: LLMChatAPI? = null,
    private val createRuntime: (() -> MemoryRuntimeServices)? = null,
) {
    private val logger = LoggerFactory.getLogger(DesktopMemoryRuntimeFactory::class.java)

    fun create(): MemoryRuntimeServicesContract =
        runCatching {
            createRuntime?.invoke() ?: MemoryRuntimeServices(
                store = SqliteMemoryStore(paths = paths),
                embeddingsApi = checkNotNull(embeddingsApi) { "Desktop embeddings API is required." },
                embeddingsFingerprint = { checkNotNull(settingsProvider).embeddingsModel.name },
                userScope = MemoryScope(MemoryScopeType.USER, LocalUserId.default()),
                vectorIndexDir = paths.stateRoot.resolve("memory-vector-index"),
                workspaceScope = detectWorkspaceScope(),
            )
        }.getOrElse { failure ->
            logger.warn("Desktop memory initialization failed; falling back to no-op memory", failure)
            NoOpMemoryRuntimeServices
        }

    private fun detectWorkspaceScope(): MemoryScope? {
        val cwd = System.getProperty("user.dir")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val path = runCatching { java.nio.file.Path.of(cwd).toAbsolutePath().normalize() }.getOrNull() ?: return null
        if (!Files.isDirectory(path)) return null
        val looksLikeWorkspace =
            Files.exists(path.resolve(".git")) ||
                Files.exists(path.resolve("settings.gradle.kts")) ||
                Files.exists(path.resolve("build.gradle.kts"))
        return if (looksLikeWorkspace) MemoryScope(MemoryScopeType.WORKSPACE, path.toString()) else null
    }
}
