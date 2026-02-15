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
        if (!settingsProvider.needsOnboarding) return

        settingsProvider.needsOnboarding = false
        emitState { copy(displayedText = ONBOARDING_DISPLAY_TEXT) }

        onboardingSpeechStartedAt = System.currentTimeMillis()
        speechUseCase.queuePrepared(ONBOARDING_SPEECH_TEXT)
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

            emitState {
                copy(
                    statusMessage = "Разрешите доступ к мониторингу ввода в настройках macOS — " +
                            "после подтверждения приложение перезапустится автоматически"
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
        private const val ONBOARDING_SPEECH_TEXT = "Привет! Я ГигаДэ́ск! умный помощник на твоем компьютере... " +
            "Сейчас я попрошу доступы к приложениям, системе и файлам, чтобы работать корректно... " +
            "Я умею пользоваться браузером, работать с почтой и календарем, работать с файлами на вашем ПК, " +
            "объяснить и переписать выделенный текст, открывать приложения, создавать заметки, отвечать на вопросы, " +
            "строить графики, диаграммы на основе данных."

        val ONBOARDING_DISPLAY_TEXT = """
            Привет! Я GigaDesk, умный помощник на твоем компьютере.
            Сейчас я попрошу доступы к приложениям, системе и файлам, чтобы работать корректно.
            Я умею:
            - Пользоваться браузером
            - Работать с почтой и календарем
            - Работать с файлами на вашем ПК
            - Объяснить и переписать выделенный текст
            - Открывать приложения, создавать заметки, отвечать на вопросы
            - Строить графики, диаграммы на основе данных


            Для запуска голосового ввода - зажми правый opt(alt)
            Для очистки контекста беседы - нажми X
            Для скрытия окна - нажми Х два раза
        """.trimIndent()
    }
}
