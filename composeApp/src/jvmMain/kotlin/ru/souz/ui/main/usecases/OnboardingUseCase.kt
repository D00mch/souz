package ru.souz.ui.main.usecases

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
import ru.souz.tool.ToolPermissionBroker
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.MainState
import ru.souz.ui.main.ToolPermissionDialogData
import kotlin.math.max
import kotlin.math.min
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

class OnboardingUseCase(
    private val settingsProvider: SettingsProvider,
    private val toolPermissionBroker: ToolPermissionBroker,
    private val speechUseCase: SpeechUseCase,
    private val relaunchApp: () -> Unit = { AppRelauncher.relaunch() },
) {
    private val l = LoggerFactory.getLogger(OnboardingUseCase::class.java)
    private var onboardingSpeechStartedAt: Long? = null
    private var permissionWatcherJob: Job? = null

    private val _outputs = Channel<MainUseCaseOutput>()
    val outputs: Flow<MainUseCaseOutput> = _outputs.consumeAsFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            toolPermissionBroker.requests.collect { request ->
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
    }

    suspend fun resolveToolPermission(requestId: Long?, approved: Boolean) {
        if (requestId == null) return
        toolPermissionBroker.resolve(requestId, approved)
        emitState { copy(toolPermissionDialog = null) }
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
                displayedText = displayText,
                chatStartTip = "",
                chatMessages = chatMessages + ChatMessage(
                    text = displayText,
                    isUser = false,
                ),
            )
        }

        onboardingSpeechStartedAt = System.currentTimeMillis()
        speechUseCase.queuePrepared(getString(Res.string.onboarding_speech_text))
    }

    fun registerNativeHook(): Boolean = runCatching {
        GlobalScreen.registerNativeHook()
        true
    }.getOrElse { e ->
        l.error("Failed to initialize hotkey listener: {}", e.message)
        false
    }

    fun handleMissingInputMonitoringPermission(scope: CoroutineScope) {
        permissionWatcherJob?.cancel()
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
                    relaunchApp()
                    return@launch
                }

                delay(retryDelayMs)
                retryDelayMs = min(retryDelayMs * 2, ONBOARDING_PERMISSION_RETRY_MAX_DELAY_MS)
            }
        }
    }

    fun rejectPendingPermissionRequest(requestId: Long?) {
        requestId?.let { toolPermissionBroker.resolve(it, approved = false) }
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
