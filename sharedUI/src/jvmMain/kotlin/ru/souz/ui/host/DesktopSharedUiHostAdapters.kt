package ru.souz.ui.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.db.ConfigStore
import ru.souz.db.REGION_EN
import ru.souz.llms.LLMModel
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.downloadPromptFor
import ru.souz.service.speech.LocalMacOsSpeechHost
import ru.souz.service.telegram.TelegramAuthState
import ru.souz.service.telegram.TelegramAuthStep
import ru.souz.tool.config.ToolSoundConfig
import ru.souz.ui.common.LocalModelDownloadPromptUi
import ru.souz.ui.common.LocalModelDownloadStateUi
import ru.souz.ui.common.LocalModelUiCoordinator
import ru.souz.ui.common.FinderService
import ru.souz.ui.common.toUi
import ru.souz.ui.settings.SupportLogSender
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

class DesktopPathOpener : PathOpener {
    override suspend fun openPath(path: String): Result<Unit> =
        FinderService.openInFinder(path)
}

class DesktopChatCommandInputSource(
    private val telegramControlBot: TelegramControlBot,
) : ChatCommandInputSource {
    override val incomingMessages: Flow<ChatCommandInput> =
        telegramControlBot.incomingMessages.map { message ->
            ChatCommandInput(
                text = message.text,
                responseDeferred = message.responseDeferred,
                isVoice = message.isVoice,
            )
        }

    override val cleanCommands: Flow<Unit> = telegramControlBot.cleanCommands
}

class DesktopLocalModelUiHost(
    private val modelStore: LocalModelStore,
    private val localLlamaRuntime: LocalLlamaRuntime,
    private val desktopIndexRepository: BackgroundIndexRefresher,
) : LocalModelUiHost {
    private val logger = LoggerFactory.getLogger(DesktopLocalModelUiHost::class.java)

    override suspend fun downloadPromptFor(model: LLMModel): LocalModelDownloadPromptUi? =
        modelStore.downloadPromptFor(model)?.toUi()

    override suspend fun startDownload(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        currentJob: Job?,
        prompt: LocalModelDownloadPromptUi?,
        updateDownloadState: suspend (LocalModelDownloadStateUi?) -> Unit,
        onSuccess: suspend (LocalModelDownloadPromptUi) -> Unit,
        onError: suspend (Throwable) -> Unit,
    ): Job? = coordinator(scope, dispatcher).startDownload(
        currentJob = currentJob,
        prompt = prompt,
        updateDownloadState = updateDownloadState,
        onSuccess = onSuccess,
        onError = onError,
    )

    override suspend fun cancelDownload(
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

    override fun rebuildIndex(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
        coordinator(scope, dispatcher).rebuildDesktopIndex()
    }

    override fun schedulePreload(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        currentJob: Job?,
        model: LLMModel,
    ): Job? = coordinator(scope, dispatcher).scheduleLocalModelPreload(
        currentJob = currentJob,
        model = model,
    )

    private fun coordinator(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ): LocalModelUiCoordinator = LocalModelUiCoordinator(
        scope = scope,
        dispatcher = dispatcher,
        modelStore = modelStore,
        localLlamaRuntime = localLlamaRuntime,
        desktopIndexRepository = desktopIndexRepository,
        logger = logger,
    )
}

class DesktopSupportLogService(
    private val sender: SupportLogSender = SupportLogSender(),
) : SupportLogService {
    override fun logDirectoryPath(): String? =
        runCatching { sender.logDirectory().toAbsolutePath().toString() }.getOrNull()

    override suspend fun sendLatestLogs(email: String): SupportLogSendResult {
        val result = sender.sendLatestLogs(email)
        return SupportLogSendResult(
            message = result.message,
            recipient = result.recipient,
            logArchivePath = result.logArchive.toAbsolutePath().toString(),
        )
    }
}

class DesktopPrivacyPolicyOpener : PrivacyPolicyOpener {
    override suspend fun openPrivacyPolicy(regionProfile: String): Result<Unit> = runCatching {
        val resourcePath = if (regionProfile == REGION_EN) {
            "support/privacy-policy.html"
        } else {
            "support/privacy-policy-ru.html"
        }
        val targetPath = extractClasspathResourceToTemp(resourcePath)
        if (!Desktop.isDesktopSupported()) error("Desktop browsing is not supported")
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) error("Desktop browsing action is not supported")
        desktop.browse(targetPath.toUri())
    }

    private fun extractClasspathResourceToTemp(resourcePath: String): Path {
        val input = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Resource not found: $resourcePath")
        val tempDir = Path.of(System.getProperty("java.io.tmpdir"), "souz-support")
        Files.createDirectories(tempDir)
        val target = tempDir.resolve(resourcePath.substringAfterLast('/'))
        input.use { stream ->
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }
}

class DesktopSettingsHostPreferences(
    private val readUseEnglishInterface: () -> Boolean? = { ConfigStore.get<Boolean>(USE_ENGLISH_INTERFACE) },
    private val writeUseEnglishInterface: (Boolean) -> Unit = { ConfigStore.put(USE_ENGLISH_INTERFACE, it) },
    val originalLocale: Locale = Locale.getDefault(),
    private val systemDisplayLocale: Locale = Locale.getDefault(Locale.Category.DISPLAY),
    private val systemFormatLocale: Locale = Locale.getDefault(Locale.Category.FORMAT),
) : SettingsHostPreferences {
    private var useEnglishInterfaceState by mutableStateOf(
        readUseEnglishInterface()
            ?: (systemDisplayLocale.language != "ru")
    )

    override var voiceSpeed: Int
        get() = ConfigStore.get(ToolSoundConfig.SPEED_KEY, ToolSoundConfig.DEFAULT_SPEED)
        set(value) {
            ConfigStore.put(ToolSoundConfig.SPEED_KEY, value)
        }

    override var useEnglishInterface: Boolean
        get() = useEnglishInterfaceState
        set(value) {
            writeUseEnglishInterface(value)
            applyInterfaceLanguage(value)
            useEnglishInterfaceState = value
        }

    override fun isLocalMacOsSpeechAvailable(): Boolean =
        LocalMacOsSpeechHost.isCurrentHost()

    fun applyInterfaceLanguage() {
        applyInterfaceLanguage(useEnglishInterfaceState)
    }

    private fun applyInterfaceLanguage(useEnglish: Boolean) {
        val locale = Locale.Builder()
            .setLanguageTag(systemDisplayLocale.toLanguageTag())
            .setLanguage(if (useEnglish) "en" else "ru")
            .build()
        Locale.setDefault(locale)
        Locale.setDefault(Locale.Category.FORMAT, systemFormatLocale)
    }

    companion object {
        private const val USE_ENGLISH_INTERFACE = "USE_ENGLISH_INTERFACE"
    }
}

class DesktopTelegramSettingsHost(
    private val telegramService: TelegramUiService,
    private val telegramControlBot: TelegramControlBot,
) : TelegramSettingsHost {
    override val authState = telegramService.authState.map(TelegramAuthState::toHostState).asStateFlowLike(
        initial = telegramService.authState.value.toHostState(),
    )

    override fun isSupported(): Boolean = telegramService.isSupported()
    override suspend fun submitPhoneNumber(phoneNumber: String) = telegramService.submitPhoneNumber(phoneNumber)
    override fun submitLoginCode(code: String) = telegramService.submitLoginCode(code)
    override fun submitTwoFaPassword(password: String) = telegramService.submitTwoFaPassword(password)
    override suspend fun requestCodeAgain(phoneNumber: String) = telegramService.requestCodeAgain(phoneNumber)
    override suspend fun cancelAuth() = telegramService.cancelAuth()
    override suspend fun logout() = telegramService.logout()
    override suspend fun createControlBot(forceNew: Boolean) = telegramService.createControlBot(forceNew)
    override suspend fun fetchActiveBotUsernameFromBotFather(): String? =
        telegramService.fetchActiveBotUsernameFromBotFather()

    override suspend fun deleteControlBot(forceNew: Boolean) = telegramService.deleteControlBot(forceNew)
    override fun restartControlBotPolling() = telegramControlBot.restartPolling()
    override fun stopControlBotPolling() = telegramControlBot.stopPolling()
    override fun isControlBotActive(): Boolean = ConfigStore.get<String>(ConfigStore.TG_BOT_TOKEN) != null
}

private fun <T> Flow<T>.asStateFlowLike(initial: T): StateFlow<T> {
    val state = MutableStateFlow(initial)
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        collect { state.value = it }
    }
    return state
}

private fun TelegramAuthState.toHostState(): TelegramHostAuthState =
    TelegramHostAuthState(
        step = when (step) {
            TelegramAuthStep.INITIALIZING -> TelegramHostAuthStep.INITIALIZING
            TelegramAuthStep.WAIT_PHONE -> TelegramHostAuthStep.PHONE
            TelegramAuthStep.WAIT_CODE -> TelegramHostAuthStep.CODE
            TelegramAuthStep.WAIT_PASSWORD -> TelegramHostAuthStep.PASSWORD
            TelegramAuthStep.READY -> TelegramHostAuthStep.CONNECTED
            TelegramAuthStep.LOGGING_OUT -> TelegramHostAuthStep.LOGGING_OUT
            TelegramAuthStep.CLOSED -> TelegramHostAuthStep.INITIALIZING
            TelegramAuthStep.ERROR -> TelegramHostAuthStep.ERROR
        },
        activePhoneMasked = activePhoneMasked,
        codeHint = codeHint,
        passwordHint = passwordHint,
        isBusy = isBusy,
        errorMessage = errorMessage,
    )
