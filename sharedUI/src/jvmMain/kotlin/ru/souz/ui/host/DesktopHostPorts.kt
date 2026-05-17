package ru.souz.ui.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ru.souz.service.telegram.TelegramAuthState

interface UiAudioRecorder {
    val audioFlow: Flow<ByteArray>
    val recordingState: StateFlow<UiAudioRecordingState>

    suspend fun logState(): Nothing
    fun start(): Boolean
    fun stop()
}

sealed interface UiAudioRecordingState {
    data object Starting : UiAudioRecordingState
    data object Recording : UiAudioRecordingState
    data object Stopping : UiAudioRecordingState
    data object Idle : UiAudioRecordingState
    data class Error(val message: String) : UiAudioRecordingState
}

interface UiSpeechPlayer {
    val isSpeaking: StateFlow<Boolean>

    fun queue(text: String, speed: Int? = null)
    fun clearQueue()
    fun playTextRand(speed: Int, vararg texts: String)
    fun playMacPing()
    fun playMacPingMsg()
    fun chooseVoice()
}

interface DesktopPermissionService {
    val isSandboxed: Boolean
    val isHeadless: Boolean

    fun requestInputMonitoringAccessPromptIfNeeded()
    fun registerNativeHook(): Boolean
    fun unregisterNativeHook()
    fun canRegisterNativeHookNow(): Boolean
    fun relaunchApp(): Boolean
    fun registerVoiceInputHotkey(
        onPressed: (Boolean) -> Unit,
        onDoubleClick: () -> Unit,
    ): VoiceInputHotkeyRegistration
}

typealias VoiceInputHotkeyRegistration = () -> Unit

interface DesktopIndexRepository {
    suspend fun storeDesktopDataDaily()
    suspend fun rebuildIndexNow()
}

typealias CalendarListProvider = () -> List<String>

interface TelegramUiService {
    val authState: StateFlow<TelegramAuthState>

    fun isSupported(): Boolean
    suspend fun submitPhoneNumber(phoneNumber: String)
    fun submitLoginCode(code: String)
    fun submitTwoFaPassword(password: String)
    suspend fun requestCodeAgain(phoneNumber: String)
    suspend fun cancelAuth()
    suspend fun logout()
    suspend fun createControlBot(forceNew: Boolean = false)
    suspend fun fetchActiveBotUsernameFromBotFather(): String?
    suspend fun deleteControlBot(forceNew: Boolean = false)
}

data class TelegramControlIncomingMessage(
    val text: String,
    val responseDeferred: CompletableDeferred<String>,
    val isVoice: Boolean = false,
)

interface TelegramControlBot {
    val incomingMessages: Flow<TelegramControlIncomingMessage>
    val cleanCommands: Flow<Unit>

    fun start()
    fun close()
    fun restartPolling()
    fun stopPolling()
}
