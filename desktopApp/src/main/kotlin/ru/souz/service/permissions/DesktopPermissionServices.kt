package ru.souz.service.permissions

import com.github.kwhat.jnativehook.GlobalScreen
import org.slf4j.LoggerFactory
import ru.souz.service.keys.HotkeyListener
import ru.souz.ui.host.DesktopPermissionService
import ru.souz.ui.host.VoiceInputHotkeyRegistration
import java.awt.GraphicsEnvironment

class MacDesktopPermissionService : DesktopPermissionService {
    private val l = LoggerFactory.getLogger(MacDesktopPermissionService::class.java)

    override val isSandboxed: Boolean
        get() = MacAppEnvironment.isSandboxed

    override val isHeadless: Boolean
        get() = GraphicsEnvironment.isHeadless()

    override fun requestInputMonitoringAccessPromptIfNeeded() {
        MacInputMonitoringAccess.requestAccessPromptIfNeeded()
    }

    override fun registerNativeHook(): Boolean =
        runCatching {
            GlobalScreen.registerNativeHook()
            true
        }.getOrElse { error ->
            l.error("Failed to initialize hotkey listener: {}", error.message)
            false
        }

    override fun unregisterNativeHook() {
        runCatching { GlobalScreen.unregisterNativeHook() }
    }

    override fun canRegisterNativeHookNow(): Boolean {
        if (isHeadless) return false
        return runCatching {
            GlobalScreen.registerNativeHook()
            GlobalScreen.unregisterNativeHook()
            true
        }.getOrElse { false }
    }

    override fun relaunchApp(): Boolean = AppRelauncher.relaunch()

    override fun registerVoiceInputHotkey(
        onPressed: (Boolean) -> Unit,
        onDoubleClick: () -> Unit,
    ): VoiceInputHotkeyRegistration {
        val listener = HotkeyListener(
            onPressed = onPressed,
            onDoubleClick = onDoubleClick,
        )
        GlobalScreen.addNativeKeyListener(listener)
        return {
            runCatching { GlobalScreen.removeNativeKeyListener(listener) }
        }
    }
}
