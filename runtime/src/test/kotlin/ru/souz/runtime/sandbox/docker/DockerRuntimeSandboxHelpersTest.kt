package ru.souz.runtime.sandbox.docker

import ru.souz.runtime.sandbox.SandboxScope
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockerRuntimeSandboxHelpersTest {
    @Test
    fun `builds stable safe container names from scope`() {
        val scope = SandboxScope(
            userId = "User Name/with:odd#chars",
            conversationId = "Conversation 123",
        )

        val first = DockerSandboxIds.defaultContainerName(scope)
        val second = DockerSandboxIds.defaultContainerName(scope)

        assertEquals(first, second)
        assertTrue(first.startsWith("souz-runtime-"))
        assertTrue(first.length <= DockerSandboxIds.MAX_CONTAINER_NAME_LENGTH)
        assertTrue(first.all { it.isLowerCase() || it.isDigit() || it == '-' })
    }

    @Test
    fun `container and host layout paths stay scoped under sandbox root`() {
        val hostRoot = Path.of("/tmp/souz-docker-test")
        val layout = DockerSandboxLayout(hostRoot)

        assertEquals("/souz/home", layout.runtimePaths.homePath)
        assertEquals("/souz/workspace", layout.runtimePaths.workspaceRootPath)
        assertEquals("/souz/state/skills", layout.runtimePaths.skillsDirPath)
        assertEquals("/souz/state/skill-validations", layout.runtimePaths.skillValidationsDirPath)
        assertEquals(hostRoot.resolve("home"), layout.hostHomeRoot)
        assertEquals(hostRoot.resolve("trash"), layout.hostTrashRoot)
        assertTrue(layout.hostManagedRoots.all { it.startsWith(hostRoot) })
    }
}
