package ru.souz.paths

import java.nio.file.Path
import ru.souz.runtime.sandbox.SandboxRuntimePaths

class SandboxSouzPaths(
    runtimePaths: SandboxRuntimePaths,
) : SouzPaths {
    override val stateRoot: Path = Path.of(runtimePaths.stateRootPath)
    override val sessionsDir: Path = Path.of(runtimePaths.sessionsDirPath)
    override val vectorIndexDir: Path = Path.of(runtimePaths.vectorIndexDirPath)
    override val logsDir: Path = Path.of(runtimePaths.logsDirPath)
    override val modelsDir: Path = Path.of(runtimePaths.modelsDirPath)
    override val nativeLibsDir: Path = Path.of(runtimePaths.nativeLibsDirPath)
    override val skillsDir: Path = Path.of(runtimePaths.skillsDirPath)
    override val skillValidationsDir: Path = Path.of(runtimePaths.skillValidationsDirPath)
}
