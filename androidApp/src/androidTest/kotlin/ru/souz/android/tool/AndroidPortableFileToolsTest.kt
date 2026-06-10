package ru.souz.android.tool

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
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
import ru.souz.tool.files.ToolReadFile

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

    private companion object {
        const val USER_ID = "android-test-user"
    }
}
