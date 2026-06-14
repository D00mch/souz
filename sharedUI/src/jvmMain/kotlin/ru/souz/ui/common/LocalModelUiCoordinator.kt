package ru.souz.ui.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.slf4j.Logger
import ru.souz.llms.LLMModel
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelDownloadProgress
import ru.souz.llms.local.LocalModelDownloadPrompt as NativeLocalModelDownloadPrompt
import ru.souz.llms.local.LocalModelProfiles
import ru.souz.llms.local.LocalModelStore
import ru.souz.ui.host.BackgroundIndexRefresher

class LocalModelUiCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val modelStore: LocalModelStore,
    private val localLlamaRuntime: LocalLlamaRuntime,
    private val desktopIndexRepository: BackgroundIndexRefresher,
    private val logger: Logger,
) {
    suspend fun startDownload(
        currentJob: Job?,
        prompt: LocalModelDownloadPromptUi?,
        updateDownloadState: suspend (LocalModelDownloadStateUi?) -> Unit,
        onSuccess: suspend (LocalModelDownloadPromptUi) -> Unit,
        onError: suspend (Throwable) -> Unit,
    ): Job? {
        val currentPrompt = prompt ?: return currentJob
        val profile = LocalModelProfiles.forAlias(currentPrompt.model.alias)
            ?: error("Local model profile not found: ${currentPrompt.model.alias}")
        currentJob?.cancelAndJoin()
        return scope.launch(dispatcher) {
            updateDownloadState(LocalModelDownloadStateUi(currentPrompt))
            runCatching {
                modelStore.downloadRequiredAssets(profile) { progress ->
                    updateDownloadState(LocalModelDownloadStateUi(currentPrompt, progress.toUi()))
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
            runCatching { desktopIndexRepository.rebuildIndexNow() }
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

fun NativeLocalModelDownloadPrompt.toUi(): LocalModelDownloadPromptUi =
    LocalModelDownloadPromptUi(
        model = model,
        profileId = profile.id,
        profileDisplayName = profile.displayName,
        downloads = downloads.map { profile ->
            LocalModelDownloadItemUi(
                id = profile.id,
                displayName = profile.displayName,
                huggingFaceRepoId = profile.huggingFaceRepoId,
                quantization = profile.quantization,
                license = profile.licenseRequirements.summary,
                requiresManualLicenseAcceptance = profile.licenseRequirements.requiresManualAcceptance,
                downloadUrl = profile.downloadUrl,
                targetPath = targetPath(profile),
            )
        },
    )

private fun LocalModelDownloadProgress.toUi(): LocalModelDownloadProgressUi =
    LocalModelDownloadProgressUi(
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        activeProfileName = activeProfileName,
        completedProfiles = completedProfiles,
        totalProfiles = totalProfiles,
    )
