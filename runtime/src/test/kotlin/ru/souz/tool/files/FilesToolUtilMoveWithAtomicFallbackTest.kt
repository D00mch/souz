package ru.souz.tool.files

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox

class FilesToolUtilMoveWithAtomicFallbackTest {
    private val createdPaths = mutableListOf<Path>()
    private val logger = mockk<org.slf4j.Logger>(relaxed = true)

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `moveWithAtomicFallback uses request scoped sandbox from invocation meta`() {
        val defaultHome = createTempDirectory("files-move-default-home-")
        val defaultState = createTempDirectory("files-move-default-state-")
        val requestHome = createTempDirectory("files-move-request-home-")
        val requestState = createTempDirectory("files-move-request-state-")
        val filesToolUtil = FilesToolUtil(
            sandboxFactory = sandboxFactory(
                mapOf(
                    "default-user" to (defaultHome to defaultState),
                    "request-user" to (requestHome to requestState),
                )
            ),
            scopeResolver = { meta -> SandboxScope(userId = meta.userId ?: "default-user") },
        )
        val source = requestHome.resolve("draft.txt").apply { writeText("request scoped content") }
        val destination = requestHome.resolve("archive/draft.txt")

        filesToolUtil.moveWithAtomicFallback(
            sourcePath = source,
            destinationPath = destination,
            logger = logger,
            meta = ToolInvocationMeta(userId = "request-user"),
        )

        assertFalse(source.exists())
        assertEquals("request scoped content", destination.readText())
        assertFalse(defaultHome.resolve("archive/draft.txt").exists())
    }

    @Test
    fun `moveWithAtomicFallback keeps default local sandbox behavior`() {
        val home = createTempDirectory("files-move-home-")
        val stateRoot = createTempDirectory("files-move-state-")
        val filesToolUtil = createFilesToolUtil(home = home, stateRoot = stateRoot)
        val source = home.resolve("notes.txt").apply { writeText("Hello from sandbox") }
        val destination = home.resolve("archive/notes.txt")

        filesToolUtil.moveWithAtomicFallback(
            sourcePath = source,
            destinationPath = destination,
            logger = logger,
        )

        assertFalse(source.exists())
        assertEquals("Hello from sandbox", destination.readText())
    }

    private fun createFilesToolUtil(
        home: Path,
        stateRoot: Path,
    ): FilesToolUtil {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        val sandbox = LocalRuntimeSandbox(
            scope = SandboxScope(userId = "user-1"),
            settingsProvider = settingsProvider,
            homePath = home,
            stateRoot = stateRoot,
        )
        return FilesToolUtil(sandbox)
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
