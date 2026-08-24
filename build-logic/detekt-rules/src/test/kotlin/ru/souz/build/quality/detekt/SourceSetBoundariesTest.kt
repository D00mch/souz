package ru.souz.build.quality.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceSetBoundariesTest {
    @Test
    fun `reports desktop and native imports from shared UI common JVM sources`() {
        val findings = lint(
            "sharedUI/src/commonJvmMain/kotlin/SharedUi.kt",
            """
            import java.awt.Desktop
            import javax.swing.JButton
            import androidx.compose.foundation.window.WindowDraggableArea
            import androidx.compose.ui.awt.ComposeWindow
            import androidx.compose.ui.window.Window
            import androidx.compose.ui.window.FutureDesktopWindowApi
            import ru.souz.llms.local.LocalChatAPI
            import ru.souz.tool.desktop.ToolGetCurrentWindow
            import ru.souz.App
            import ru.souz.LocalWindowScope
            import ru.souz.di.sharedUiDesktopDiModule
            import ru.souz.di.sharedUiDiModule
            import ru.souz.*
            import ru.souz.di.*
            """,
        )

        assertEquals(14, findings.size)
        assertTrue(findings.all { it.message.contains(":sharedUI commonJvmMain") })
    }

    @Test
    fun `allows portable Compose windows and ordinary JVM APIs in shared UI common JVM sources`() {
        val findings = lint(
            "sharedUI/src/commonJvmMain/kotlin/SharedUi.kt",
            """
            import androidx.compose.material3.Text
            import androidx.compose.ui.window.Dialog
            import androidx.compose.ui.window.DialogProperties
            import androidx.compose.ui.window.Popup
            import androidx.compose.ui.window.PopupProperties
            import java.time.Clock
            """,
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `reports host imports from portable common sources`() {
        val findings = lint(
            "/repo/sharedUI/src/commonMain/kotlin/PortableUi.kt",
            """
            import java.time.Clock
            import platform.AppKit.NSWindow
            import ru.souz.service.audio.AudioRecorder
            import androidx.compose.ui.window.*
            """,
        )

        assertEquals(4, findings.size)
        assertTrue(findings.all { it.message.contains("commonMain must remain portable") })
    }

    @Test
    fun `rejects Android-only AndroidX and allows reviewed multiplatform APIs in portable common sources`() {
        val findings = lint(
            "sharedUI/src/commonMain/kotlin/PortableUi.kt",
            """
            import androidx.appcompat.app.AppCompatActivity
            import androidx.compose.desktop.ui.tooling.preview.Preview
            import androidx.compose.material3.Text
            import androidx.lifecycle.ViewModel
            """,
        )

        assertEquals(2, findings.size)
        assertTrue(findings.any { it.message.contains("androidx.appcompat.app.AppCompatActivity") })
        assertTrue(findings.any { it.message.contains("androidx.compose.desktop.ui.tooling.preview.Preview") })
    }

    @Test
    fun `reports UI and host implementation imports from core production modules`() {
        val findings = lint(
            "agent/src/main/kotlin/AgentRuntime.kt",
            """
            import androidx.compose.runtime.Composable
            import ru.souz.backend.client.PublicClientService
            import ru.souz.di.sharedUiDesktopDiModule
            import ru.souz.service.speech.MacOsSpeechBridge
            """,
        )

        assertEquals(4, findings.size)
        assertTrue(findings.all { it.message.contains(":agent main") })
    }

    @Test
    fun `reports UI and desktop-only imports from backend production sources`() {
        val findings = lint(
            "backend/src/main/kotlin/BackendRuntime.kt",
            """
            import ru.souz.ui.main.MainViewModel
            import ru.souz.service.audio.AudioRecorder
            import ru.souz.tool.telegram.ToolTelegramSend
            import ru.souz.llms.local.LocalChatAPI
            import ru.souz.tool.skills.ToolInvokeSkill
            """,
        )

        assertEquals(3, findings.size)
        assertTrue(findings.all { it.message.contains(":backend main") })
    }

    @Test
    fun `allows shared speech and telegram contracts in backend production sources`() {
        val findings = lint(
            "backend/src/main/kotlin/BackendRuntime.kt",
            """
            import ru.souz.service.speech.SpeechRecognitionProvider
            import ru.souz.service.telegram.TelegramAuthStep
            """,
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `ignores test sources and unknown layouts`() {
        val import = "import java.awt.Desktop"

        assertEquals(0, lint("sharedUI/src/jvmTest/kotlin/SharedUiTest.kt", import).size)
        assertEquals(0, lint("scratch/SharedUi.kt", import).size)
    }

    @Test
    fun `normalizes Windows source paths`() {
        val findings = lint(
            "C:\\repo\\sharedUI\\src\\commonJvmMain\\kotlin\\SharedUi.kt",
            "import java.awt.Desktop",
        )

        assertEquals(1, findings.size)
    }

    private fun lint(path: String, code: String) =
        SourceSetBoundaries(Config.empty, path).lint(code.trimIndent())
}
