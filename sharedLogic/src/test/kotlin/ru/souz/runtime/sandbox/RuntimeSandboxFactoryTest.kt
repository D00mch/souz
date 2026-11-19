package ru.souz.runtime.sandbox

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox

class RuntimeSandboxFactoryTest {
    private val settingsProvider = mockk<SettingsProvider> {
        every { forbiddenFolders } returns emptyList()
    }

    @Test
    fun `missing sandbox mode defaults to local`() {
        val factory = createFactory(mode = null)

        val sandbox = factory.create(SandboxScope(userId = "user-1"))

        assertIs<LocalRuntimeSandbox>(sandbox)
    }

    @Test
    fun `explicit local sandbox mode uses local runtime sandbox`() {
        val factory = createFactory(mode = "local")

        val sandbox = factory.create(SandboxScope(userId = "user-1"))

        assertIs<LocalRuntimeSandbox>(sandbox)
    }

    @Test
    fun `explicit docker sandbox mode uses docker runtime sandbox`() {
        val factory = createFactory(mode = "docker")

        val sandbox = factory.create(SandboxScope(userId = "user-1"))

        assertIs<DockerRuntimeSandbox>(sandbox)
    }

    @Test
    fun `docker mode fails fast with clear error when docker is unavailable`() {
        val factory = DefaultRuntimeSandboxFactory(
            settingsProvider = settingsProvider,
            modeResolver = RuntimeSandboxModeResolver { "docker" },
            localHomePath = Path.of("/tmp/local-home"),
            localStateRoot = Path.of("/tmp/local-state"),
            dockerHostRoot = Path.of("/tmp/docker-root"),
            dockerSandboxCreator = { _, _, _, _, _ ->
                throw IllegalStateException("Docker CLI is unavailable for sandbox mode docker")
            },
        )

        val error = assertFailsWith<IllegalStateException> {
            factory.create(SandboxScope(userId = "user-1"))
        }

        assertEquals("Docker CLI is unavailable for sandbox mode docker", error.message)
    }

    @Test
    fun `desktop defaults and local mode do not expose prepared attachments`() {
        val scope = SandboxScope(userId = "user-1")
        for (factory in listOf(createFactory("docker"), createFactory("local", preparedAttachmentsEnabled = true))) {
            assertNull(factory.preparedAttachmentsRoot())
            assertNull(factory.preparedAttachments(scope))
        }
    }

    @Test
    fun `attachment enabled docker uses a separate container and owner mount without moving the workspace`() {
        val scope = SandboxScope(userId = "user-1")
        val ordinary = createFactory("docker").create(scope) as DockerRuntimeSandbox
        val factory = createFactory("docker", preparedAttachmentsEnabled = true)
        val managed = factory.create(scope) as DockerRuntimeSandbox

        assertEquals(ordinary.hostRoot, managed.hostRoot)
        assertEquals(ordinary.runtimePaths, managed.runtimePaths)
        assertNotEquals(ordinary.containerName, managed.containerName)
        assertEquals(factory.preparedAttachments(scope)?.hostDirectory, managed.layout.hostPreparedAttachments)
        assertNotEquals(factory.preparedAttachments(scope), factory.preparedAttachments(SandboxScope("user-2")))
        assertTrue(managed.layout.hostPreparedAttachments!!.startsWith(factory.preparedAttachmentsRoot()!!))
    }

    private fun createFactory(mode: String?, preparedAttachmentsEnabled: Boolean = false): DefaultRuntimeSandboxFactory =
        DefaultRuntimeSandboxFactory(
            settingsProvider = settingsProvider,
            modeResolver = RuntimeSandboxModeResolver { mode },
            localHomePath = Path.of("/tmp/local-home"),
            localStateRoot = Path.of("/tmp/local-state"),
            dockerHostRoot = Path.of("/tmp/docker-root"),
            preparedAttachmentsEnabled = preparedAttachmentsEnabled,
            dockerSandboxCreator = { scope, hostRoot, imageName, containerName, attachments ->
                DockerRuntimeSandbox(
                    scope = scope,
                    hostRoot = hostRoot.resolve(scope.userId),
                    imageName = imageName,
                    containerName = containerName,
                    autoStart = false,
                    preparedAttachments = attachments,
                )
            },
        )
}
