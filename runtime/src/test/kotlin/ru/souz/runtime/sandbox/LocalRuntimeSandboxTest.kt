package ru.souz.runtime.sandbox

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalRuntimeSandboxTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `resolves tilde and relative paths inside sandbox home`() {
        val home = createTempDirectory("sandbox-home-")
        val stateRoot = createTempDirectory("sandbox-state-")
        val sandbox = createSandbox(home = home, stateRoot = stateRoot)

        val tildePath = sandbox.fileSystem.resolvePath("~/notes.txt")
        val relativePath = sandbox.fileSystem.resolvePath("drafts/today.md")

        assertTrue(Path.of(tildePath.sandboxPath).endsWith("notes.txt"))
        assertTrue(Path.of(relativePath.sandboxPath).endsWith(Path.of("drafts/today.md")))
        assertEquals(home.toString(), sandbox.runtimePaths.homePath)
        assertEquals(stateRoot.resolve("skills").toString(), sandbox.runtimePaths.skillsDirPath)
    }

    @Test
    fun `marks forbidden descendants as unsafe after sandbox path expansion`() {
        val home = createTempDirectory("sandbox-home-")
        val stateRoot = createTempDirectory("sandbox-state-")
        val forbiddenRoot = home.resolve("Library").createDirectories()
        val safeFile = home.resolve("workspace/notes.txt").apply {
            parent.createDirectories()
            writeText("ok")
        }
        val forbiddenFile = forbiddenRoot.resolve("secret.txt").apply {
            parent.createDirectories()
            writeText("nope")
        }
        val sandbox = createSandbox(
            home = home,
            stateRoot = stateRoot,
            forbiddenFolders = listOf(forbiddenRoot.toString()),
        )

        val safePath = sandbox.fileSystem.resolvePath(safeFile.toString())
        val blockedPath = sandbox.fileSystem.resolvePath(forbiddenFile.toString())

        assertTrue(sandbox.fileSystem.isPathSafe(safePath))
        assertFalse(sandbox.fileSystem.isPathSafe(blockedPath))
    }

    @Test
    fun `new file under symlink parent cannot escape home`() {
        val home = createTempDirectory("sandbox-home-")
        val outside = createTempDirectory("sandbox-outside-")
        val stateRoot = createTempDirectory("sandbox-state-")

        val link = home.resolve("escape")
        runCatching {
            Files.createSymbolicLink(link, outside)
        }.getOrElse { error ->
            error("Could not create symlink for sandbox escape test: ${error.message}")
        }

        assertTrue(Files.isSymbolicLink(link), "Test setup failed: link is not a symlink")

        val sandbox = createSandbox(home = home, stateRoot = stateRoot)
        val target = sandbox.fileSystem.resolvePath("~/escape/new.txt")

        assertThrows<Exception> {
            sandbox.fileSystem.writeText(target, "should not escape")
        }

        assertFalse(Files.exists(outside.resolve("new.txt")))
    }

    @Test
    fun `list descendants excludes symlink files that resolve outside sandbox`() {
        val home = createTempDirectory("home-")
        val outside = createTempDirectory("outside-")
        val safeFile = home.resolve("notes.txt").apply { writeText("safe") }
        val outsideFile = outside.resolve("secret.txt").apply { writeText("secret") }
        Files.createSymbolicLink(home.resolve("escape.txt"), outsideFile)
        val sandbox = createSandbox(home, stateRoot = createTempDirectory("state-"))

        val descendants = sandbox.fileSystem.listDescendants(
            root = sandbox.fileSystem.resolveExistingDirectory(home.toString()),
        )

        assertTrue(descendants.any { it.path == safeFile.toRealPath().toString() })
        assertFalse(descendants.any { it.rawPath == home.resolve("escape.txt").toString() })
        assertFalse(descendants.any { it.path == outsideFile.toRealPath().toString() })
    }

    private fun createSandbox(
        home: Path,
        stateRoot: Path,
        forbiddenFolders: List<String> = emptyList(),
    ): LocalRuntimeSandbox {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns forbiddenFolders
        return LocalRuntimeSandbox(
            scope = SandboxScope(userId = "user-1"),
            settingsProvider = settingsProvider,
            homePath = home,
            stateRoot = stateRoot,
        )
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)
}
