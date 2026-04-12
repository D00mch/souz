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

suspend fun startLocalModelDownload(
    currentJob: Job?,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    prompt: LocalModelDownloadPrompt?,
    store: LocalModelStore,
    updateDownloadState: suspend (LocalModelDownloadState?) -> Unit,
    onSuccess: suspend (LocalModelDownloadPrompt) -> Unit,
    onError: suspend (Throwable) -> Unit,
): Job? {
    val currentPrompt = prompt ?: return currentJob
    currentJob?.cancelAndJoin()
    return scope.launch(dispatcher) {
        updateDownloadState(LocalModelDownloadState(currentPrompt))
        runCatching {
            store.downloadRequiredAssets(currentPrompt.profile) { progress ->
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

suspend fun cancelLocalModelDownload(
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

fun launchDesktopIndexRebuild(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    repository: DesktopInfoRepository,
    logger: Logger,
) {
    scope.launch(dispatcher) {
        runCatching { repository.rebuildIndexNow() }
            .onFailure { error ->
                logger.warn("Desktop index rebuild failed: {}", error.message)
            }
    }
}

fun launchLocalModelPreload(
    currentJob: Job?,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    model: LLMModel,
    runtime: LocalLlamaRuntime,
    logger: Logger,
): Job? {
    if (!LocalModelProfiles.isLocalModelAlias(model.alias)) {
        currentJob?.cancel()
        return null
    }
    currentJob?.cancel()
    return scope.launch(dispatcher) {
        runCatching { runtime.preload(model.alias) }
            .onFailure { error ->
                if (error !is CancellationException) {
                    logger.warn("Local model preload failed for {}: {}", model.alias, error.message)
                }
            }
    }
}
