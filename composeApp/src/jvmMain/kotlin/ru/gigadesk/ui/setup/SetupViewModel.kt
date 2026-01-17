package ru.gigadesk.ui.setup

import androidx.lifecycle.viewModelScope
import com.github.kwhat.jnativehook.GlobalScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.audio.Say
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.permissions.AppRelauncher
import ru.gigadesk.ui.BaseViewModel

class SetupViewModel(
    override val di: DI,
) : BaseViewModel<SetupState, SetupEvent, SetupEffect>(), DIAware {

    private val logger = LoggerFactory.getLogger(SetupViewModel::class.java)
    private val settingsProvider: SettingsProvider by di.instance()
    private val say: Say by di.instance()
    private var permissionWatcherJob: Job? = null

    init {
        viewModelScope.launch {
            val gigaChatKey = settingsProvider.gigaChatKey.orEmpty()
            val saluteSpeechKey = settingsProvider.saluteSpeechKey.orEmpty()
            val inputMonitoringGranted = canRegisterNativeHookNow()
            val accessibilityGranted = isAccessibilityPermissionGranted()
            updateState(
                gigaChatKey = gigaChatKey,
                saluteSpeechKey = saluteSpeechKey,
                inputMonitoringGranted = inputMonitoringGranted,
                accessibilityGranted = accessibilityGranted,
                isCheckingPermissions = false
            )
        }
    }

    override fun initialState(): SetupState = SetupState()

    override suspend fun handleEvent(event: SetupEvent) {
        when (event) {
            is SetupEvent.InputGigaChatKey -> {
                settingsProvider.gigaChatKey = event.key
                updateState(event.key, currentState.saluteSpeechKey)
            }
            is SetupEvent.InputSaluteSpeechKey -> {
                settingsProvider.saluteSpeechKey = event.key
                updateState(currentState.gigaChatKey, event.key)
            }
            SetupEvent.CheckPermissions -> refreshPermissions(relaunchIfGranted = true)
            SetupEvent.OpenInputMonitoringSettings -> openInputMonitoringSettings()
            SetupEvent.OpenAccessibilitySettings -> openAccessibilitySettings()
            SetupEvent.ChooseVoice -> runCatching { say.chooseVoice() }
                .onFailure { logger.warn("Failed to open voice settings", it) }
            SetupEvent.Proceed -> {
                if (currentState.canProceed) {
                    send(SetupEffect.OpenMain)
                }
            }
        }
    }

    override suspend fun handleSideEffect(effect: SetupEffect) = Unit

    private suspend fun updateState(
        gigaChatKey: String,
        saluteSpeechKey: String,
        inputMonitoringGranted: Boolean = currentState.isInputMonitoringPermissionGranted,
        accessibilityGranted: Boolean = currentState.isAccessibilityPermissionGranted,
        isCheckingPermissions: Boolean = currentState.isCheckingPermissions,
    ) {
        val messages = resolveMissingMessages(gigaChatKey, saluteSpeechKey)
        val canProceed = gigaChatKey.isNotBlank() &&
            saluteSpeechKey.isNotBlank() &&
            inputMonitoringGranted &&
            accessibilityGranted
        setState {
            copy(
                gigaChatKey = gigaChatKey,
                saluteSpeechKey = saluteSpeechKey,
                missingMessages = messages,
                isInputMonitoringPermissionGranted = inputMonitoringGranted,
                isAccessibilityPermissionGranted = accessibilityGranted,
                isCheckingPermissions = isCheckingPermissions,
                canProceed = canProceed
            )
        }
    }

    private fun resolveMissingMessages(gigaChatKey: String, saluteSpeechKey: String): List<String> {
        val noGigaChatKey = gigaChatKey.isBlank()
        val noGigaVoiceKey = saluteSpeechKey.isBlank()
        val messages = mutableListOf<String>()

        if (noGigaChatKey && noGigaVoiceKey) {
            messages += "Не могу найти ключи для GigaChat и Salute Speech"
        } else if (noGigaChatKey) {
            messages += "Не могу найти ключи для GigaChat"
        } else if (noGigaVoiceKey) {
            messages += "Не могу найти ключи для Salute Speech"
        }
        return messages
    }

    private fun refreshPermissions(relaunchIfGranted: Boolean) {
        permissionWatcherJob?.cancel()
        permissionWatcherJob = viewModelScope.launch {
            setState { copy(isCheckingPermissions = true) }
            delay(400)
            val inputMonitoringGranted = canRegisterNativeHookNow()
            val accessibilityGranted = isAccessibilityPermissionGranted()
            updateState(
                gigaChatKey = currentState.gigaChatKey,
                saluteSpeechKey = currentState.saluteSpeechKey,
                inputMonitoringGranted = inputMonitoringGranted,
                accessibilityGranted = accessibilityGranted,
                isCheckingPermissions = false
            )
            if (inputMonitoringGranted && accessibilityGranted && relaunchIfGranted) {
                logger.info("All required permissions granted, relaunching application")
                AppRelauncher.relaunch()
            }
        }
    }

    private fun openInputMonitoringSettings() {
        runCatching {
            ProcessBuilder(
                "open",
                "x-apple.systempreferences:com.apple.preference.security?Privacy_ListenEvent"
            ).start()
        }.onFailure { logger.warn("Failed to open input monitoring settings", it) }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            ProcessBuilder(
                "open",
                "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
            ).start()
        }.onFailure { logger.warn("Failed to open accessibility settings", it) }
    }

    private fun canRegisterNativeHookNow(): Boolean = runCatching {
        GlobalScreen.registerNativeHook()
        GlobalScreen.unregisterNativeHook()
        true
    }.getOrElse { false }

    private fun isAccessibilityPermissionGranted(): Boolean = runCatching {
        val process = ProcessBuilder(
            "osascript",
            "-e",
            "tell application \"System Events\" to return UI elements enabled"
        ).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        result.equals("true", ignoreCase = true)
    }.getOrElse { false }

    override fun onCleared() {
        super.onCleared()
        permissionWatcherJob?.cancel()
    }
}
