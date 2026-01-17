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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
            if (shouldOpenMain(gigaChatKey, saluteSpeechKey, inputMonitoringGranted, accessibilityGranted)) {
                send(SetupEffect.OpenMain)
            }
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
                    settingsProvider.isSetupCompleted = true
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

    private fun shouldOpenMain(
        gigaChatKey: String,
        saluteSpeechKey: String,
        inputMonitoringGranted: Boolean,
        accessibilityGranted: Boolean,
    ): Boolean {
        if (!settingsProvider.isSetupCompleted) {
            return false
        }
        return gigaChatKey.isNotBlank() &&
            saluteSpeechKey.isNotBlank() &&
            inputMonitoringGranted &&
            accessibilityGranted
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
            val appPath = resolveAppBundlePath()
            ProcessBuilder(
                "open",
                "x-apple.systempreferences:com.apple.preference.security?Privacy_ListenEvent"
            ).start()
            if (appPath != null) {
                val script = """
                    tell application "System Settings"
                        activate
                    end tell
                    delay 0.5
                    tell application "System Events"
                        tell process "System Settings"
                            repeat until exists window 1
                                delay 0.2
                            end repeat
                            if exists (button "Add" of window 1) then
                                click button "Add" of window 1
                            else if exists (button "+" of window 1) then
                                click button "+" of window 1
                            end if
                        end tell
                    end tell
                    delay 0.3
                    tell application "System Events"
                        keystroke "G" using {command down, shift down}
                        delay 0.2
                        keystroke "$appPath"
                        delay 0.2
                        keystroke return
                        delay 0.2
                        keystroke return
                    end tell
                """.trimIndent()
                ProcessBuilder("osascript", "-e", script).start()
            }
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

    private fun resolveAppBundlePath(): String? {
        val packagedAppPath = System.getProperty("jpackage.app-path")
        if (!packagedAppPath.isNullOrBlank() && Files.exists(Paths.get(packagedAppPath))) {
            return packagedAppPath
        }

        val command = ProcessHandle.current().info().command().orElse(null)
        val commandMatch = command?.let { Regex("(.+\\.app)").find(it) }
        if (commandMatch != null) {
            return Paths.get(commandMatch.groupValues[1]).toString()
        }

        val argsMatch = ProcessHandle.current().info().arguments().orElse(null)
            ?.firstNotNullOfOrNull { arg -> Regex("(.+\\.app)").find(arg) }
        if (argsMatch != null) {
            return Paths.get(argsMatch.groupValues[1]).toString()
        }

        val appName = System.getProperty("jpackage.app-name")?.ifBlank { null } ?: "GigaDesk"
        val candidates = listOf(
            Path.of("/Applications", "$appName.app"),
            Path.of(System.getProperty("user.home"), "Applications", "$appName.app")
        )
        return candidates.firstOrNull { Files.exists(it) }?.toString()
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
