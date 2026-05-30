package ru.souz.paths

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultSouzPathsTest {
    @Test
    fun `builds storage subdirectories from state root`() {
        val paths = DefaultSouzPaths(stateRoot = Path.of("/tmp/souz-state"))

        assertEquals(Path.of("/tmp/souz-state"), paths.stateRoot)
        assertEquals(Path.of("/tmp/souz-state/sessions"), paths.sessionsDir)
        assertEquals(Path.of("/tmp/souz-state/vector-index"), paths.vectorIndexDir)
        assertEquals(Path.of("/tmp/souz-state/logs"), paths.logsDir)
        assertEquals(Path.of("/tmp/souz-state/models"), paths.modelsDir)
        assertEquals(Path.of("/tmp/souz-state/native"), paths.nativeLibsDir)
        assertEquals(Path.of("/tmp/souz-state/skills"), paths.skillsDir)
        assertEquals(Path.of("/tmp/souz-state/skill-validations"), paths.skillValidationsDir)
    }
}
