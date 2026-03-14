package ru.souz.ui.main.usecases

import androidx.annotation.MainThread
import com.github.kwhat.jnativehook.GlobalScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.permissions.AppRelauncher
import ru.souz.permissions.MacAppEnvironment
import ru.souz.permissions.MacInputMonitoringAccess
import ru.souz.tool.SelectionApprovalSource
import ru.souz.tool.ToolPermissionBroker
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.MainState
import ru.souz.ui.main.SelectionDialogCandidateUi
import ru.souz.ui.main.SelectionDialogData
import ru.souz.ui.main.ToolPermissionDialogData
import kotlin.math.max
import kotlin.math.min
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import java.util.concurrent.ConcurrentHashMap

class PermissionsUseCase(
    private val settingsProvider: SettingsProvider,
    private val toolPermissionBroker: ToolPermissionBroker,
    private val selectionApprovalSources: Set<SelectionApprovalSource>,
    private val speechUseCase: SpeechUseCase,
    private val relaunchApp: () -> Boolean = { AppRelauncher.relaunch() },
) {
    private val l = LoggerFactory.getLogger(PermissionsUseCase::class.java)
    private var onboardingSpeechStartedAt: Long? = null
    private var permissionWatcherJob: Job? = null
    private val selectionApprovalSourcesById: Map<String, SelectionApprovalSource> =
        selectionApprovalSources.associateBy { it.sourceId }

    private val _outputs = Channel<MainUseCaseOutput>()
    val outputs: Flow<MainUseCaseOutput> = _outputs.consumeAsFlow()

    @MainThread
    fun start(scope: CoroutineScope) {
        scope.launch {
            toolPermissionBroker.requests.collect { request ->
                if (settingsProvider.notificationSoundEnabled) {
                    speechUseCase.playMacPingMsgSafely(scope)
                }
                emitState {
                    copy(
                        toolPermissionDialog = ToolPermissionDialogData(
                            requestId = request.id,
                            description = request.description,
                            params = request.params.toSortedMap(),
                        )
                    )
                }
            }
        }
        selectionApprovalSourcesById.values.forEach { source ->
            scope.launch {
                source.requests.collect { request ->
                    if (settingsProvider.notificationSoundEnabled) {
                        speechUseCase.playMacPingMsgSafely(scope)
                    }
                    emitState {
                        copy(
                            selectionDialog = SelectionDialogData(
                                sourceId = request.sourceId,
                                requestId = request.requestId,
                                title = request.title,
                                message = request.message,
                                confirmText = request.confirmText,
                                cancelText = request.cancelText,
                                candidates = request.candidates.map { candidate ->
                                    SelectionDialogCandidateUi(
                                        id = candidate.id,
                                        title = candidate.title,
                                        badge = candidate.badge,
                                        meta = candidate.meta,
                                        preview = candidate.preview,
                                    )
                                },
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun resolveToolPermission(requestId: Long?, approved: Boolean) {
        if (requestId == null) return
        toolPermissionBroker.resolve(requestId, approved)
        emitState { copy(toolPermissionDialog = null) }
    }

    @MainThread
    suspend fun resolveSelectionDialog(
        sourceId: String?,
        requestId: Long?,
        selectedCandidateId: Long?,
    ) {
        if (sourceId == null || requestId == null) return
        selectionApprovalSourcesById[sourceId]?.resolve(requestId, selectedCandidateId)
        emitState { copy(selectionDialog = null) }
    }

    suspend fun runOnboardingIfNeeded() {
        // TODO: do we need both checks? Can we refactor to use only one?
        if (settingsProvider.onboardingCompleted) return
        if (!settingsProvider.needsOnboarding) return

        settingsProvider.needsOnboarding = false
        settingsProvider.onboardingCompleted = true
        val displayText = getString(Res.string.onboarding_display_text)
        emitState {
            copy(
                isSpeaking = true,
                chatStartTip = "",
                chatMessages = chatMessages + ChatMessage(
                    isVoice = true,
                    text = displayText,
                    isUser = false,
                ),
            )
        }

        onboardingSpeechStartedAt = System.currentTimeMillis()
        speechUseCase.queuePrepared(getString(Res.string.onboarding_speech_text))
    }

    fun registerNativeHook(): Boolean {
        MacInputMonitoringAccess.requestAccessPromptIfNeeded()
        return runCatching {
            GlobalScreen.registerNativeHook()
            true
        }.getOrElse { e ->
            l.error("Failed to initialize hotkey listener: {}", e.message)
            false
        }
    }

    fun handleMissingInputMonitoringPermission(scope: CoroutineScope) {
        permissionWatcherJob?.cancel()
        if (MacAppEnvironment.isSandboxed) {
            permissionWatcherJob = scope.launch {
                val statusMsg = getString(Res.string.onboarding_input_permission_sandbox_limited)
                emitState { copy(statusMessage = statusMsg) }
            }
            return
        }

        permissionWatcherJob = scope.launch {
            val startAt = onboardingSpeechStartedAt
            if (startAt != null) {
                val elapsed = System.currentTimeMillis() - startAt
                val waitMs = max(0, ONBOARDING_PERMISSION_DELAY_MS - elapsed)
                if (waitMs > 0) {
                    delay(waitMs)
                }
            }

            val statusMsg = getString(Res.string.onboarding_input_permission_request)
            emitState {
                copy(
                    statusMessage = statusMsg
                )
            }

            var retryDelayMs = ONBOARDING_PERMISSION_RETRY_INITIAL_DELAY_MS
            while (isActive) {
                if (canRegisterNativeHookNow()) {
                    l.info("Input monitoring permission granted, relaunching application")
                    if (!relaunchApp()) {
                        l.error("Automatic relaunch failed after input monitoring permission was granted")
                        val restartFailedMsg = getString(Res.string.onboarding_input_permission_restart_failed)
                        emitState { copy(statusMessage = restartFailedMsg) }
                    }
                    return@launch
                }

                delay(retryDelayMs)
                retryDelayMs = min(retryDelayMs * 4, ONBOARDING_PERMISSION_RETRY_MAX_DELAY_MS)
            }
        }
    }

    fun rejectPendingPermissionRequest(requestId: Long?) {
        requestId?.let { toolPermissionBroker.resolve(it, approved = false) }
    }

    fun rejectPendingSelectionDialog(sourceId: String?, requestId: Long?) {
        if (sourceId == null || requestId == null) return
        selectionApprovalSourcesById[sourceId]?.resolve(requestId, null)
    }

    fun onCleared() {
        permissionWatcherJob?.cancel()
    }

    private fun canRegisterNativeHookNow(): Boolean = runCatching {
        GlobalScreen.registerNativeHook()
        GlobalScreen.unregisterNativeHook()
        true
    }.getOrElse { false }

    private suspend fun emitState(reduce: MainState.() -> MainState) {
        _outputs.send(MainUseCaseOutput.State(reduce))
    }

    companion object {
        private const val ONBOARDING_PERMISSION_DELAY_MS = 100000
        private const val ONBOARDING_PERMISSION_RETRY_INITIAL_DELAY_MS = 4_000L
        private const val ONBOARDING_PERMISSION_RETRY_MAX_DELAY_MS = 64_000L
    }
}
