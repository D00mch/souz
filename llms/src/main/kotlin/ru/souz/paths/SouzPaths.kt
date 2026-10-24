package ru.souz.paths

import java.nio.file.Path

interface SouzPaths {
    val stateRoot: Path
    val sessionsDir: Path
    val skillsDir: Path
    val skillValidationsDir: Path
}

class DefaultSouzPaths(
    override val stateRoot: Path = defaultStateRoot(),
) : SouzPaths {
    override val sessionsDir: Path = stateRoot.resolve("sessions")
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
