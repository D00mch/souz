package ru.gigadesk.ui.main.usecases

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
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.permissions.AppRelauncher
import ru.gigadesk.tool.ToolPermissionBroker
import ru.gigadesk.ui.main.MainState
import ru.gigadesk.ui.main.ToolPermissionDialogData
import kotlin.math.max
import gigadesk.composeapp.generated.resources.Res
import gigadesk.composeapp.generated.resources.*
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
        emitState { copy(displayedText = displayText) }

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

            while (isActive) {
                delay(4_000)
                if (canRegisterNativeHookNow()) {
                    l.info("Input monitoring permission granted, relaunching application")
                    relaunchApp()
                }
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
    }
}
