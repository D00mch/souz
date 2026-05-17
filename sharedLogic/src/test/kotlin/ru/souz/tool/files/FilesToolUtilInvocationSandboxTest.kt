package ru.souz.tool.files

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox

class FilesToolUtilInvocationSandboxTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `singleton file tool resolves active sandbox from invocation meta`() {
        val userOneHome = createTempDirectory("files-meta-home-1-")
        val userOneState = createTempDirectory("files-meta-state-1-")
        val userTwoHome = createTempDirectory("files-meta-home-2-")
        val userTwoState = createTempDirectory("files-meta-state-2-")
        val tool = ToolNewFile(
            FilesToolUtil(
                sandboxFactory = sandboxFactory(
                    mapOf(
                        "user-1" to (userOneHome to userOneState),
                        "user-2" to (userTwoHome to userTwoState),
                    )
                ),
                scopeResolver = { meta -> SandboxScope(userId = meta.userId) },
            )
        )

        tool.invoke(ToolNewFile.Input(path = "~/notes.txt", text = "one"), ToolInvocationMeta(userId = "user-1"))
        tool.invoke(ToolNewFile.Input(path = "~/notes.txt", text = "two"), ToolInvocationMeta(userId = "user-2"))

        assertEquals("one", userOneHome.resolve("notes.txt").readText())
        assertEquals("two", userTwoHome.resolve("notes.txt").readText())
        assertFalse(userOneHome.resolve("notes.txt").readText() == userTwoHome.resolve("notes.txt").readText())
    }

    @Test
    fun `local metadata still resolves local default sandbox without request scoped state`() {
        val home = createTempDirectory("files-meta-default-home-")
        val stateRoot = createTempDirectory("files-meta-default-state-")
        val filesToolUtil = FilesToolUtil(
            sandboxFactory = sandboxFactory(mapOf("local-user" to (home to stateRoot))),
            scopeResolver = { SandboxScope(userId = "local-user") },
        )

        val resolved = filesToolUtil.resolvePath("~/draft.txt")

        assertEquals(home.resolve("draft.txt").toString(), resolved.path)
    }

    private fun sandboxFactory(layouts: Map<String, Pair<Path, Path>>): RuntimeSandboxFactory {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        return RuntimeSandboxFactory { scope ->
            val (home, stateRoot) = layouts[scope.userId] ?: error("No sandbox layout for ${scope.userId}")
            LocalRuntimeSandbox(
                scope = scope,
                settingsProvider = settingsProvider,
                homePath = home,
                stateRoot = stateRoot,
            )
        }
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)
}
