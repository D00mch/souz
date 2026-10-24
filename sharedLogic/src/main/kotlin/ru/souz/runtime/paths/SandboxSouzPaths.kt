package ru.souz.runtime.paths

import ru.souz.paths.SouzPaths
import java.nio.file.Path
import ru.souz.runtime.sandbox.SandboxRuntimePaths

class SandboxSouzPaths(
    runtimePaths: SandboxRuntimePaths,
) : SouzPaths {
    override val stateRoot: Path = Path.of(runtimePaths.stateRootPath)
    override val sessionsDir: Path = Path.of(runtimePaths.sessionsDirPath)
    override val skillsDir: Path = Path.of(runtimePaths.skillsDirPath)
    override val skillValidationsDir: Path = Path.of(runtimePaths.skillValidationsDirPath)
}
