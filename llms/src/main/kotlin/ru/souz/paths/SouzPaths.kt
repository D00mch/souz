package ru.souz.paths

import java.nio.file.Path

// TODO: extract a separate file system related module
// TODO: Have VirtualBoxing implemented there
interface SouzPaths {
    val stateRoot: Path
    val sessionsDir: Path
    val vectorIndexDir: Path
    val logsDir: Path
    val modelsDir: Path
    val nativeLibsDir: Path
    val skillsDir: Path
    val skillValidationsDir: Path
}

class DefaultSouzPaths(
    override val stateRoot: Path = defaultStateRoot(),
) : SouzPaths {
    override val sessionsDir: Path = stateRoot.resolve("sessions")
    override val vectorIndexDir: Path = stateRoot.resolve("vector-index")
    override val logsDir: Path = stateRoot.resolve("logs")
    override val modelsDir: Path = stateRoot.resolve("models")
    override val nativeLibsDir: Path = stateRoot.resolve("native")
    override val skillsDir: Path = stateRoot.resolve("skills")
    override val skillValidationsDir: Path = stateRoot.resolve("skill-validations")

    companion object {
        fun defaultStateRoot(): Path = homeDirectory().resolve(".local").resolve("state").resolve("souz")

        fun homeDirectory(): Path = Path.of(
            listOf(
                System.getProperty("user.home"),
                System.getenv("HOME"),
            ).firstOrNull { !it.isNullOrBlank() }?.trim()
                ?: error("Unable to resolve the current user home directory."),
        )
    }
}
