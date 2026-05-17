package ru.souz.skills.registry

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.paths.SandboxSouzPaths
import ru.souz.runtime.sandbox.SandboxRuntimePaths

class SandboxSouzPathsTest {
    @Test
    fun `maps sandbox runtime paths into souz paths`() {
        val runtimePaths = SandboxRuntimePaths(
            homePath = "/sandbox/home",
            workspaceRootPath = "/sandbox/workspace",
            stateRootPath = "/sandbox/state",
            sessionsDirPath = "/sandbox/state/sessions",
            vectorIndexDirPath = "/sandbox/state/vector-index",
            logsDirPath = "/sandbox/state/logs",
            modelsDirPath = "/sandbox/state/models",
            nativeLibsDirPath = "/sandbox/state/native",
            skillsDirPath = "/sandbox/state/skills",
            skillValidationsDirPath = "/sandbox/state/skill-validations",
        )

        val paths = SandboxSouzPaths(runtimePaths)

        assertEquals(Path.of("/sandbox/state"), paths.stateRoot)
        assertEquals(Path.of("/sandbox/state/sessions"), paths.sessionsDir)
        assertEquals(Path.of("/sandbox/state/vector-index"), paths.vectorIndexDir)
        assertEquals(Path.of("/sandbox/state/logs"), paths.logsDir)
        assertEquals(Path.of("/sandbox/state/models"), paths.modelsDir)
        assertEquals(Path.of("/sandbox/state/native"), paths.nativeLibsDir)
        assertEquals(Path.of("/sandbox/state/skills"), paths.skillsDir)
        assertEquals(Path.of("/sandbox/state/skill-validations"), paths.skillValidationsDir)
    }
}
