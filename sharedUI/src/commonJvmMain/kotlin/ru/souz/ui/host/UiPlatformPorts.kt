package ru.souz.ui.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import ru.souz.llms.LLMModel
import ru.souz.ui.common.LocalModelDownloadPromptUi
import ru.souz.ui.common.LocalModelDownloadStateUi

fun interface ExternalLinkOpener {
    fun open(url: String): Result<Unit>
}

interface PermissionPromptService {
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

object NoopPermissionPromptService : PermissionPromptService {
    override val isSandboxed: Boolean = false
    override val isHeadless: Boolean = true

    override fun requestInputMonitoringAccessPromptIfNeeded() = Unit
    override fun registerNativeHook(): Boolean = false
    override fun unregisterNativeHook() = Unit
    override fun canRegisterNativeHookNow(): Boolean = false
    override fun relaunchApp(): Boolean = false

    override fun registerVoiceInputHotkey(
        onPressed: (Boolean) -> Unit,
        onDoubleClick: () -> Unit,
    ): VoiceInputHotkeyRegistration = {}
}

interface BackgroundIndexRefresher {
    suspend fun storeDesktopDataDaily()
    suspend fun rebuildIndexNow()
}

object NoopBackgroundIndexRefresher : BackgroundIndexRefresher {
    override suspend fun storeDesktopDataDaily() = Unit
    override suspend fun rebuildIndexNow() = Unit
}

typealias CalendarListProvider = () -> List<String>

val NoopCalendarListProvider: CalendarListProvider = { emptyList() }

fun interface PathOpener {
    suspend fun openPath(path: String): Result<Unit>
}

object NoopPathOpener : PathOpener {
    override suspend fun openPath(path: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Opening local paths is not available on this host."))
}

data class ChatCommandInput(
    val text: String,
    val responseDeferred: CompletableDeferred<String>,
    val isVoice: Boolean = false,
)

interface ChatCommandInputSource {
    val incomingMessages: Flow<ChatCommandInput>
    val cleanCommands: Flow<Unit>
}

object NoopChatCommandInputSource : ChatCommandInputSource {
    override val incomingMessages: Flow<ChatCommandInput> = emptyFlow()
    override val cleanCommands: Flow<Unit> = emptyFlow()
}

interface LocalModelUiHost {
    suspend fun downloadPromptFor(model: LLMModel): LocalModelDownloadPromptUi?

    suspend fun startDownload(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        currentJob: Job?,
        prompt: LocalModelDownloadPromptUi?,
        updateDownloadState: suspend (LocalModelDownloadStateUi?) -> Unit,
        onSuccess: suspend (LocalModelDownloadPromptUi) -> Unit,
        onError: suspend (Throwable) -> Unit,
    ): Job?

    suspend fun cancelDownload(
        currentJob: Job?,
        hasActiveDownload: Boolean,
        clearDownloadState: suspend () -> Unit,
        onCancelled: suspend () -> Unit,
    ): Job?

    fun rebuildIndex(scope: CoroutineScope, dispatcher: CoroutineDispatcher)

    fun schedulePreload(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        currentJob: Job?,
        model: LLMModel,
    ): Job?
}

object NoopLocalModelUiHost : LocalModelUiHost {
    override suspend fun downloadPromptFor(model: LLMModel): LocalModelDownloadPromptUi? = null

    override suspend fun startDownload(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        currentJob: Job?,
        prompt: LocalModelDownloadPromptUi?,
        updateDownloadState: suspend (LocalModelDownloadStateUi?) -> Unit,
        onSuccess: suspend (LocalModelDownloadPromptUi) -> Unit,
        onError: suspend (Throwable) -> Unit,
    ): Job? = currentJob

    override suspend fun cancelDownload(
        currentJob: Job?,
        hasActiveDownload: Boolean,
        clearDownloadState: suspend () -> Unit,
        onCancelled: suspend () -> Unit,
    ): Job? {
        clearDownloadState()
        return null
    }

    override fun rebuildIndex(scope: CoroutineScope, dispatcher: CoroutineDispatcher) = Unit

    override fun schedulePreload(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        currentJob: Job?,
        model: LLMModel,
    ): Job? {
        currentJob?.cancel()
        return null
    }
}

enum class TelegramHostAuthStep {
    INITIALIZING,
    PHONE,
    CODE,
    PASSWORD,
    CONNECTED,
    LOGGING_OUT,
    ERROR,
}

data class TelegramHostAuthState(
    val step: TelegramHostAuthStep = TelegramHostAuthStep.INITIALIZING,
    val activePhoneMasked: String? = null,
    val codeHint: String? = null,
    val passwordHint: String? = null,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
)

interface TelegramSettingsHost {
    val authState: StateFlow<TelegramHostAuthState>

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
    fun restartControlBotPolling()
    fun stopControlBotPolling()
    fun isControlBotActive(): Boolean
}

object NoopTelegramSettingsHost : TelegramSettingsHost {
    override val authState: StateFlow<TelegramHostAuthState> =
        MutableStateFlow(TelegramHostAuthState(step = TelegramHostAuthStep.ERROR))

    override fun isSupported(): Boolean = false
    override suspend fun submitPhoneNumber(phoneNumber: String) = Unit
    override fun submitLoginCode(code: String) = Unit
    override fun submitTwoFaPassword(password: String) = Unit
    override suspend fun requestCodeAgain(phoneNumber: String) = Unit
    override suspend fun cancelAuth() = Unit
    override suspend fun logout() = Unit
    override suspend fun createControlBot(forceNew: Boolean) = Unit
    override suspend fun fetchActiveBotUsernameFromBotFather(): String? = null
    override suspend fun deleteControlBot(forceNew: Boolean) = Unit
    override fun restartControlBotPolling() = Unit
    override fun stopControlBotPolling() = Unit
    override fun isControlBotActive(): Boolean = false
}

data class SupportLogSendResult(
    val message: String,
    val recipient: String,
    val logArchivePath: String,
)

interface SupportLogService {
    fun logDirectoryPath(): String?
    suspend fun sendLatestLogs(email: String): SupportLogSendResult
}

object NoopSupportLogService : SupportLogService {
    override fun logDirectoryPath(): String? = null

    override suspend fun sendLatestLogs(email: String): SupportLogSendResult =
        throw UnsupportedOperationException("Support log sending is not available on this host.")
}

interface PrivacyPolicyOpener {
    suspend fun openPrivacyPolicy(regionProfile: String): Result<Unit>
}

object NoopPrivacyPolicyOpener : PrivacyPolicyOpener {
    override suspend fun openPrivacyPolicy(regionProfile: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Opening the privacy policy is not available on this host."))
}

interface SettingsHostPreferences {
    var voiceSpeed: Int
    fun isLocalMacOsSpeechAvailable(): Boolean
}

class InMemorySettingsHostPreferences(
    override var voiceSpeed: Int = 230,
    private val localMacOsSpeechAvailable: Boolean = false,
) : SettingsHostPreferences {
    override fun isLocalMacOsSpeechAvailable(): Boolean = localMacOsSpeechAvailable
}
