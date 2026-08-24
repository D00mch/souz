package ru.souz.build.quality.detekt

import dev.detekt.api.Config
import dev.detekt.test.junit.KotlinCoreEnvironmentTest
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.KotlinEnvironmentContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@KotlinCoreEnvironmentTest
class SourceSetBoundariesTest(
    private val environment: KotlinEnvironmentContainer,
) {
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
            import ru.souz.di.mainDiModule
            import ru.souz.tool.ToolRunBashCommand
            import ru.souz.tool.files.ToolRequestSelection
            import ru.souz.ui.common.DesktopExternalLinkOpener
            import ru.souz.ui.host.DesktopPathOpener
            import ru.souz.ui.main.usecases.DesktopPathPicker
            import ru.souz.db.ConfigStore
            import ru.souz.service.mcp.McpClientManager
            import ru.souz.*
            import ru.souz.di.*
            """,
        )

        assertEquals(22, findings.size)
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
            import ru.souz.tool.ToolRunBashCommand
            import ru.souz.ambient.DefaultAmbientTranscriptionService
            import ru.souz.db.ConfigStore
            import ru.souz.runtime.files.createDefaultFilesToolUtil
            import ru.souz.service.mcp.McpClientManager
            import androidx.compose.ui.window.*
            """,
        )

        assertEquals(9, findings.size)
        assertTrue(findings.all { it.message.contains("commonMain must remain portable") })
    }

    @Test
    fun `reports fully qualified host references from shared sources`() {
        val sharedUiFindings = lint(
            "sharedUI/src/commonJvmMain/kotlin/SharedUi.kt",
            """
            val desktop = java.awt.Desktop.getDesktop()
            val window: androidx.compose.ui.awt.ComposeWindow? = null
            val clock = java.time.Clock.systemUTC()
            val opener: ru.souz.ui.host.DesktopPathOpener? = null
            """,
        )
        val commonFindings = lint(
            "sharedUI/src/commonMain/kotlin/PortableUi.kt",
            """
            val clock = java.time.Clock.systemUTC()
            val context = androidx.compose.ui.platform.LocalContext.current
            val viewModel: androidx.lifecycle.ViewModel? = null
            val ambient: ru.souz.ambient.DefaultAmbientTranscriptionService? = null

            @androidx.compose.runtime.Composable
            fun Content() {
                androidx.compose.material3.Text("Portable")
            }
            """,
        )

        assertEquals(3, sharedUiFindings.size, sharedUiFindings.joinToString { it.message })
        assertTrue(sharedUiFindings.any { it.message.contains("java.awt.Desktop.getDesktop") })
        assertTrue(sharedUiFindings.any { it.message.contains("androidx.compose.ui.awt.ComposeWindow") })
        assertEquals(3, commonFindings.size, commonFindings.joinToString { it.message })
        assertTrue(commonFindings.any { it.message.contains("java.time.Clock.systemUTC") })
        assertTrue(commonFindings.any { it.message.contains("androidx.compose.ui.platform.LocalContext.current") })
    }

    @Test
    fun `reports fully qualified host references from core and backend sources`() {
        val coreFindings = lint(
            "agent/src/main/kotlin/AgentRuntime.kt",
            "val service: ru.souz.backend.client.PublicClientService? = null",
        )
        val backendFindings = lint(
            "backend/src/main/kotlin/BackendRuntime.kt",
            "val viewModel: ru.souz.ui.main.MainViewModel? = null",
        )

        assertEquals(1, coreFindings.size)
        assertEquals(1, backendFindings.size)
    }

    @Test
    fun `reports package declarations that expose forbidden APIs without imports`() {
        val javaFindings = lint(
            "sharedUI/src/commonJvmMain/kotlin/DesktopPackage.kt",
            """
            package java.awt

            val desktop = Desktop.getDesktop()
            """,
        )
        val composeFindings = lint(
            "sharedUI/src/commonJvmMain/kotlin/WindowPackage.kt",
            """
            package androidx.compose.ui.window

            val window: Window? = null
            """,
        )

        assertEquals(1, javaFindings.size)
        assertTrue(javaFindings.single().message.contains("Package 'java.awt'"))
        assertEquals(1, composeFindings.size)
        assertTrue(composeFindings.single().message.contains("Package 'androidx.compose.ui.window'"))
    }

    @Test
    fun `ignores qualified member chains rooted in local values`() {
        val findings = lint(
            "sharedUI/src/commonJvmMain/kotlin/SharedUi.kt",
            """
            class DesktopApi {
                fun getDesktop() = Unit
            }
            class AwtNamespace(val Desktop: DesktopApi)
            class JavaNamespace(val awt: AwtNamespace)

            fun use(java: JavaNamespace) {
                java.awt.Desktop.getDesktop()
            }
            """,
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `rejects unreviewed AndroidX siblings and wildcards in portable common sources`() {
        val findings = lint(
            "sharedUI/src/commonMain/kotlin/PortableUi.kt",
            """
            import androidx.appcompat.app.AppCompatActivity
            import androidx.compose.desktop.ui.tooling.preview.Preview
            import androidx.compose.foundation.AndroidExternalSurface
            import androidx.compose.material3.*
            import androidx.compose.material3.Text
            import androidx.compose.ui.platform.*
            import androidx.compose.ui.platform.LocalClipboardManager
            import androidx.compose.ui.platform.LocalContext
            import androidx.compose.ui.window.Dialog
            import androidx.lifecycle.*
            import androidx.lifecycle.LifecycleService
            import androidx.lifecycle.ViewModel
            import androidx.lifecycle.viewModelScope
            """,
        )

        assertEquals(8, findings.size)
        assertTrue(findings.any { it.message.contains("androidx.appcompat.app.AppCompatActivity") })
        assertTrue(findings.any { it.message.contains("androidx.compose.desktop.ui.tooling.preview.Preview") })
        assertTrue(findings.any { it.message.contains("androidx.compose.foundation.AndroidExternalSurface") })
        assertTrue(findings.any { it.message.contains("androidx.compose.material3.*") })
        assertTrue(findings.any { it.message.contains("androidx.compose.ui.platform.*") })
        assertTrue(findings.any { it.message.contains("androidx.compose.ui.platform.LocalContext") })
        assertTrue(findings.any { it.message.contains("androidx.lifecycle.*") })
        assertTrue(findings.any { it.message.contains("androidx.lifecycle.LifecycleService") })
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
            import ru.souz.tool.ToolRunBashCommand
            import ru.souz.tool.files.ToolRequestSelection
            """,
        )

        assertEquals(6, findings.size)
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
            import ru.souz.di.mainDiModule
            import ru.souz.tool.ToolRunBashCommand
            import ru.souz.tool.files.ToolRequestSelection
            """,
        )

        assertEquals(6, findings.size)
        assertTrue(findings.all { it.message.contains(":backend main") })
    }

    @Test
    fun `allows shared speech and telegram contracts in backend production sources`() {
        val findings = lint(
            "backend/src/main/kotlin/BackendRuntime.kt",
            """
            import ru.souz.service.speech.SpeechRecognitionProvider
            import ru.souz.service.telegram.TelegramAuthStep
            import ru.souz.db.ConfigStore
            import ru.souz.runtime.files.createDefaultFilesToolUtil
            import ru.souz.service.mcp.McpClientManager
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

    @Test
    fun `configured source roots fail closed for overlap and unmapped conventional paths`() {
        val roots = listOf(
            "/repo/sharedUI/portable|sharedUI|commonJvmMain|true|false",
            "/repo/sharedUI/portable/tests|sharedUI|jvmTest|false|false",
        )
        val import = "import java.awt.Desktop"

        assertEquals(1, lint("/repo/sharedUI/portable/SharedUi.kt", import, roots).size)
        assertEquals(1, lint("/repo/sharedUI/portable/tests/SharedUiTest.kt", import, roots).size)
        assertEquals(1, lint("/repo/sharedUI/src/commonJvmMain/Unmapped.kt", import, roots).size)
    }

    private fun lint(path: String, code: String, sourceRoots: List<String> = emptyList()) =
        SourceSetBoundaries(Config.empty, path, sourceRoots).lintWithContext(environment, code.trimIndent())
}
