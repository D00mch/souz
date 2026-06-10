package ru.souz.android.tool

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.android.agent.AndroidAgentRuntime
import ru.souz.android.settings.AndroidSettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.tool.ToolCategory
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.files.ToolReadFile
import ru.souz.tool.shell.ToolRunShellCommand

@RunWith(AndroidJUnit4::class)
class AndroidPortableFileToolsTest {
    @Test
    fun readFileReadsFromAndroidAppPrivateSandboxAndCatalogIncludesIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = AndroidAgentRuntime(context, AndroidSettingsProvider(context))
        val direct = runtime.di.direct
        val fileSystem = direct.instance<RuntimeSandboxFactory>()
            .create(SandboxScope(userId = USER_ID))
            .fileSystem
        val tool = direct.instance<ToolReadFile>()
        val catalog = direct.instance<AgentToolCatalog>()
        val testDirectory = "~/readfile-tests/${UUID.randomUUID()}"
        val testFile = "$testDirectory/message.txt"

        try {
            fileSystem.writeText(
                path = fileSystem.resolvePath(testFile),
                content = "Android read file\n",
            )

            val result = tool.invoke(
                ToolReadFile.Input(path = testFile),
                ToolInvocationMeta(userId = USER_ID),
            )

            assertEquals("Android read file\n", result)
            assertTrue("ReadFile" in catalog.toolsByCategory.getValue(ToolCategory.FILES))
        } finally {
            runCatching {
                fileSystem.delete(fileSystem.resolvePath(testDirectory), recursively = true)
            }
        }
    }

    @Test
    fun runShellCommandUsesAndroidSandboxShellAndCatalogIncludesIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsProvider = AndroidSettingsProvider(context).apply {
            safeModeEnabled = true
        }
        val runtime = AndroidAgentRuntime(context, settingsProvider)
        val direct = runtime.di.direct
        val tool = direct.instance<ToolRunShellCommand>()
        val broker = direct.instance<ToolPermissionBroker>()
        val catalog = direct.instance<AgentToolCatalog>()

        val result = async {
            tool.suspendInvoke(
                ToolRunShellCommand.Input(
                    script = "printf android-shell",
                    timeoutMillis = 15_000,
                ),
                ToolInvocationMeta(userId = USER_ID),
            )
        }
        val request = withTimeout(5_000) { broker.requests.first() }
        assertEquals("Run shell command", request.description)
        broker.resolve(request.id, approved = true)

        val output = result.await()
        assertTrue(output, output.contains("exitCode: 0"))
        assertTrue(output, output.contains("android-shell"))
        assertTrue("RunShellCommand" in catalog.toolsByCategory.getValue(ToolCategory.SHELL))
    }

    private companion object {
        const val USER_ID = "android-test-user"
    }
}
