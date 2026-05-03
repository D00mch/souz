package ru.souz.runtime.sandbox.docker

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DockerRuntimeSandboxIntegrationTest {
    private val createdPaths = mutableListOf<Path>()
    private val sandboxes = mutableListOf<DockerRuntimeSandbox>()

    @AfterTest
    fun cleanup() {
        sandboxes.asReversed().forEach { sandbox ->
            runCatching { sandbox.close() }
        }
        sandboxes.clear()
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @BeforeEach
    fun checkEnv() {
        assumeTrue(dockerTestsEnabled(), "Run with SOUZ_TEST_DOCKER=1 to enable Docker integration tests.")
    }

    @Test
    fun `container starts and bash python node are available`() = runTest {
        val sandbox = createSandbox()

        assertEquals("/souz/home", sandbox.runtimePaths.homePath)
        assertTrue(Files.isDirectory(sandbox.hostRoot.resolve("home")))

        val bashResult = sandbox.commandExecutor.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.BASH,
                script = "printf '%s' \"\$HOME\"",
            ),
        )
        val pythonResult = sandbox.commandExecutor.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.PYTHON,
                script = "print('python-ok')",
            ),
        )
        val nodeResult = sandbox.commandExecutor.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.NODE,
                script = "process.stdout.write('node-ok')",
            ),
        )

        assertEquals(0, bashResult.exitCode)
        assertEquals("/souz/home", bashResult.stdout)
        assertEquals(0, pythonResult.exitCode)
        assertEquals("python-ok", pythonResult.stdout.trim())
        assertEquals(0, nodeResult.exitCode)
        assertEquals("node-ok", nodeResult.stdout.trim())
    }

    @Test
    fun `file system operations work through mounted sandbox state`() = runTest {
        val sandbox = createSandbox()
        val logger = LoggerFactory.getLogger("DockerRuntimeSandboxIntegrationTest")

        val file = sandbox.fileSystem.resolvePath("~/notes/today.txt")
        sandbox.fileSystem.writeText(file, "hello docker sandbox")

        val moved = sandbox.fileSystem.resolvePath("~/archive/today.txt")
        sandbox.fileSystem.move(
            source = sandbox.fileSystem.resolveExistingFile("~/notes/today.txt"),
            destination = moved,
            createParents = true,
            logger = logger,
        )

        val descendants = sandbox.fileSystem.listDescendants(
            root = sandbox.fileSystem.resolveExistingDirectory("~"),
            includeHidden = true,
        )
        assertEquals("hello docker sandbox", sandbox.fileSystem.readText(sandbox.fileSystem.resolveExistingFile("~/archive/today.txt")))
        val trashed = sandbox.fileSystem.moveToTrash(
            path = sandbox.fileSystem.resolveExistingFile("~/archive/today.txt"),
            logger = logger,
        )

        assertTrue(descendants.any { it.path == "/souz/home/archive/today.txt" })
        assertTrue(trashed.path.startsWith("/souz/trash/"))
        assertEquals("hello docker sandbox", sandbox.fileSystem.readText(trashed))
        assertEquals("hello docker sandbox", sandbox.hostRoot.resolve("trash").resolve(trashed.name).readText())
    }

    @Test
    fun `rejects escaped paths`() {
        val sandbox = createSandbox()

        assertThrows<Exception> {
            sandbox.fileSystem.resolveExistingFile("/etc/passwd")
        }
        val escaped = sandbox.fileSystem.resolvePath("../../etc/passwd")
        assertFalse(sandbox.fileSystem.isPathSafe(escaped))
        assertThrows<Exception> {
            sandbox.fileSystem.writeText(escaped, "nope")
        }
    }

    @Test
    fun `list descendants and container cleanup leave only host files behind`() = runTest {
        val sandbox = createSandbox()
        val workspaceDir = sandbox.fileSystem.resolvePath("~/workspace/demo")
        sandbox.fileSystem.createDirectory(workspaceDir)
        sandbox.fileSystem.writeText(sandbox.fileSystem.resolvePath("~/workspace/demo/one.txt"), "1")
        sandbox.fileSystem.writeText(sandbox.fileSystem.resolvePath("~/workspace/demo/.two.txt"), "2")

        val visible = sandbox.fileSystem.listDescendants(
            root = sandbox.fileSystem.resolveExistingDirectory("~/workspace"),
        )
        val hidden = sandbox.fileSystem.listDescendants(
            root = sandbox.fileSystem.resolveExistingDirectory("~/workspace"),
            includeHidden = true,
        )

        assertTrue(visible.any { it.name == "one.txt" })
        assertFalse(visible.any { it.name == ".two.txt" })
        assertTrue(hidden.any { it.name == ".two.txt" })
        assertNotNull(sandbox.fileSystem.openInputStream(sandbox.fileSystem.resolveExistingFile("~/workspace/demo/one.txt")).use { it.read() })

        val containerName = sandbox.containerName
        sandbox.close()
        sandboxes.remove(sandbox)

        assertTrue(sandbox.hostRoot.resolve("home/workspace/demo/one.txt").toFile().exists())
        val inspect = docker("inspect", containerName)
        assertTrue(inspect.exitCode != 0 || !inspect.stdout.contains(containerName))
    }

    private fun createSandbox(): DockerRuntimeSandbox {
        ensureDockerImage()
        val hostRoot = createTempDirectory("docker-runtime-sandbox-")
        val sandbox = DockerRuntimeSandbox(
            scope = SandboxScope(
                userId = "docker-test-user",
                conversationId = "case-${System.nanoTime()}",
            ),
            hostRoot = hostRoot,
            imageName = TEST_IMAGE_NAME,
            removeContainerOnClose = true,
        )
        sandboxes += sandbox
        return sandbox
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)

    private fun ensureDockerImage() {
        val inspect = docker("image", "inspect", TEST_IMAGE_NAME)
        if (inspect.exitCode == 0) {
            return
        }
        val contextDir = Path.of("runtime/src/test/resources/docker/runtime-sandbox")
        val build = docker("build", "-t", TEST_IMAGE_NAME, contextDir.toString())
        check(build.exitCode == 0) {
            "Failed to build Docker sandbox test image.\nstdout:\n${build.stdout}\nstderr:\n${build.stderr}\nRun locally with SOUZ_TEST_DOCKER=1 ./gradlew :runtime:test"
        }
    }

    private fun dockerTestsEnabled(): Boolean = System.getenv("SOUZ_TEST_DOCKER") == "1"

    private fun docker(vararg args: String): DockerTestProcessResult {
        val process = ProcessBuilder(listOf("docker") + args)
            .directory(Path.of(".").toFile())
            .redirectErrorStream(false)
            .start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = thread(start = true, name = "docker-test-stdout") {
            process.inputStream.bufferedReader().use { reader ->
                stdout.append(reader.readText())
            }
        }
        val stderrReader = thread(start = true, name = "docker-test-stderr") {
            process.errorStream.bufferedReader().use { reader ->
                stderr.append(reader.readText())
            }
        }
        val exitCode = process.waitFor()
        stdoutReader.join()
        stderrReader.join()
        return DockerTestProcessResult(exitCode, stdout.toString(), stderr.toString())
    }

    private data class DockerTestProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private companion object {
        const val TEST_IMAGE_NAME = "souz-runtime-sandbox:test"
    }
}
