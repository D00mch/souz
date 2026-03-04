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
import ru.souz.service.telegram.TelegramChatCandidate
import ru.souz.service.telegram.TelegramContactCandidate
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.telegram.TelegramChatSelectionBroker
import ru.souz.tool.telegram.TelegramContactSelectionBroker
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.MainState
import ru.souz.ui.main.TelegramChatCandidateUi
import ru.souz.ui.main.TelegramChatSelectionDialogData
import ru.souz.ui.main.TelegramContactCandidateUi
import ru.souz.ui.main.TelegramContactSelectionDialogData
import ru.souz.ui.main.ToolPermissionDialogData
import kotlin.math.max
import kotlin.math.min
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

class PermissionsUseCase(
    private val settingsProvider: SettingsProvider,
    private val toolPermissionBroker: ToolPermissionBroker,
    private val telegramContactSelectionBroker: TelegramContactSelectionBroker,
    private val telegramChatSelectionBroker: TelegramChatSelectionBroker,
    private val speechUseCase: SpeechUseCase,
    private val relaunchApp: () -> Boolean = { AppRelauncher.relaunch() },
) {
    private val l = LoggerFactory.getLogger(PermissionsUseCase::class.java)
    private var onboardingSpeechStartedAt: Long? = null
    private var permissionWatcherJob: Job? = null

    private val _outputs = Channel<MainUseCaseOutput>()
    val outputs: Flow<MainUseCaseOutput> = _outputs.consumeAsFlow()

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
        scope.launch {
            telegramContactSelectionBroker.requests.collect { request ->
                if (settingsProvider.notificationSoundEnabled) {
                    speechUseCase.playMacPingMsgSafely(scope)
                }
                emitState {
                    copy(
                        telegramContactSelectionDialog = TelegramContactSelectionDialogData(
                            requestId = request.id,
                            query = request.query,
                            candidates = request.candidates.map(::toContactCandidateUi),
                        )
                    )
                }
            }
        }
        scope.launch {
            telegramChatSelectionBroker.requests.collect { request ->
                if (settingsProvider.notificationSoundEnabled) {
                    speechUseCase.playMacPingMsgSafely(scope)
                }
                emitState {
                    copy(
                        telegramChatSelectionDialog = TelegramChatSelectionDialogData(
                            requestId = request.id,
                            query = request.query,
                            candidates = request.candidates.map(::toChatCandidateUi),
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

    suspend fun resolveTelegramContactSelection(requestId: Long?, selectedUserId: Long?) {
        if (requestId == null) return
        telegramContactSelectionBroker.resolve(requestId, selectedUserId)
        emitState { copy(telegramContactSelectionDialog = null) }
    }

    suspend fun resolveTelegramChatSelection(requestId: Long?, selectedChatId: Long?) {
        if (requestId == null) return
        telegramChatSelectionBroker.resolve(requestId, selectedChatId)
        emitState { copy(telegramChatSelectionDialog = null) }
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

    fun rejectPendingTelegramContactSelection(requestId: Long?) {
        requestId?.let { telegramContactSelectionBroker.resolve(it, null) }
    }

    fun rejectPendingTelegramChatSelection(requestId: Long?) {
        requestId?.let { telegramChatSelectionBroker.resolve(it, null) }
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

    private fun toContactCandidateUi(candidate: TelegramContactCandidate): TelegramContactCandidateUi {
        return TelegramContactCandidateUi(
            userId = candidate.userId,
            displayName = candidate.displayName,
            username = candidate.username,
            phoneMasked = candidate.phoneMasked,
            isContact = candidate.isContact,
            lastMessageText = candidate.lastMessageText,
        )
    }

    private fun toChatCandidateUi(candidate: TelegramChatCandidate): TelegramChatCandidateUi {
        return TelegramChatCandidateUi(
            chatId = candidate.chatId,
            title = candidate.title,
            unreadCount = candidate.unreadCount,
            isPrivateChat = candidate.linkedUserId != null,
            lastMessageText = candidate.lastMessageText,
        )
    }

    companion object {
        private const val ONBOARDING_PERMISSION_DELAY_MS = 100000
        private const val ONBOARDING_PERMISSION_RETRY_INITIAL_DELAY_MS = 4_000L
        private const val ONBOARDING_PERMISSION_RETRY_MAX_DELAY_MS = 64_000L
    }
}
