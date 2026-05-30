package ru.souz.runtime.sandbox

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
            dockerSandboxCreator = { scope, hostRoot, imageName, containerName ->
                throw IllegalStateException("Docker CLI is unavailable for sandbox mode docker")
            },
        )

        val error = assertFailsWith<IllegalStateException> {
            factory.create(SandboxScope(userId = "user-1"))
        }

        assertEquals("Docker CLI is unavailable for sandbox mode docker", error.message)
    }

    private fun createFactory(mode: String?): DefaultRuntimeSandboxFactory =
        DefaultRuntimeSandboxFactory(
            settingsProvider = settingsProvider,
            modeResolver = RuntimeSandboxModeResolver { mode },
            localHomePath = Path.of("/tmp/local-home"),
            localStateRoot = Path.of("/tmp/local-state"),
            dockerHostRoot = Path.of("/tmp/docker-root"),
            dockerSandboxCreator = { scope, hostRoot, imageName, containerName ->
                DockerRuntimeSandbox(
                    scope = scope,
                    hostRoot = hostRoot.resolve(scope.userId),
                    imageName = imageName,
                    containerName = containerName,
                    autoStart = false,
                )
            },
        )
}
