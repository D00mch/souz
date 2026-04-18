package ru.souz.ui.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.slf4j.Logger
import ru.souz.db.DesktopInfoRepository
import ru.souz.llms.LLMModel
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelDownloadPrompt
import ru.souz.llms.local.LocalModelDownloadState
import ru.souz.llms.local.LocalModelProfiles
import ru.souz.llms.local.LocalModelStore

class LocalModelUiCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val modelStore: LocalModelStore,
    private val localLlamaRuntime: LocalLlamaRuntime,
    private val desktopInfoRepository: DesktopInfoRepository,
    private val logger: Logger,
) {
    suspend fun startDownload(
        currentJob: Job?,
        prompt: LocalModelDownloadPrompt?,
        updateDownloadState: suspend (LocalModelDownloadState?) -> Unit,
        onSuccess: suspend (LocalModelDownloadPrompt) -> Unit,
        onError: suspend (Throwable) -> Unit,
    ): Job? {
        val currentPrompt = prompt ?: return currentJob
        currentJob?.cancelAndJoin()
        return scope.launch(dispatcher) {
            updateDownloadState(LocalModelDownloadState(currentPrompt))
            runCatching {
                modelStore.downloadRequiredAssets(currentPrompt.profile) { progress ->
                    updateDownloadState(LocalModelDownloadState(currentPrompt, progress))
                }
            }.onSuccess {
                onSuccess(currentPrompt)
            }.onFailure { error ->
                updateDownloadState(null)
                if (error !is CancellationException) {
                    onError(error)
                }
            }
        }
    }

    suspend fun cancelDownload(
        currentJob: Job?,
        hasActiveDownload: Boolean,
        clearDownloadState: suspend () -> Unit,
        onCancelled: suspend () -> Unit,
    ): Job? {
        currentJob?.cancelAndJoin()
        clearDownloadState()
        if (hasActiveDownload) {
            onCancelled()
        }
        return null
    }

    fun rebuildDesktopIndex() {
        scope.launch(dispatcher) {
            runCatching { desktopInfoRepository.rebuildIndexNow() }
                .onFailure { error ->
                    logger.warn("Desktop index rebuild failed: {}", error.message)
                }
        }
    }

    fun scheduleLocalModelPreload(currentJob: Job?, model: LLMModel): Job? {
        if (!LocalModelProfiles.isLocalModelAlias(model.alias)) {
            currentJob?.cancel()
            return null
        }
        currentJob?.cancel()
        return scope.launch(dispatcher) {
            runCatching { localLlamaRuntime.preload(model.alias) }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        logger.warn("Local model preload failed for {}: {}", model.alias, error.message)
                    }
                }
        }
    }
}
