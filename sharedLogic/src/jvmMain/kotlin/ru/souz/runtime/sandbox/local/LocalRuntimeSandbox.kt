package ru.souz.runtime.sandbox.local

import java.nio.file.Path
import ru.souz.db.SettingsProvider
import ru.souz.paths.DefaultSouzPaths
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.SandboxScope

class LocalRuntimeSandbox(
    override val scope: SandboxScope,
    settingsProvider: SettingsProvider,
    homePath: Path = DefaultSouzPaths.homeDirectory(),
    stateRoot: Path = DefaultSouzPaths.defaultStateRoot(),
    workspaceRoot: Path? = runCatching { Path.of(System.getProperty("user.dir")) }.getOrNull(),
) : RuntimeSandbox {
    override val mode: SandboxMode = SandboxMode.LOCAL
    override val runtimePaths: SandboxRuntimePaths = SandboxRuntimePaths(
        homePath = homePath.toAbsolutePath().normalize().toString(),
        workspaceRootPath = workspaceRoot?.toAbsolutePath()?.normalize()?.toString(),
        stateRootPath = stateRoot.toAbsolutePath().normalize().toString(),
        sessionsDirPath = stateRoot.resolve("sessions").toAbsolutePath().normalize().toString(),
        vectorIndexDirPath = stateRoot.resolve("vector-index").toAbsolutePath().normalize().toString(),
        logsDirPath = stateRoot.resolve("logs").toAbsolutePath().normalize().toString(),
        modelsDirPath = stateRoot.resolve("models").toAbsolutePath().normalize().toString(),
        nativeLibsDirPath = stateRoot.resolve("native").toAbsolutePath().normalize().toString(),
        skillsDirPath = stateRoot.resolve("skills").toAbsolutePath().normalize().toString(),
        skillValidationsDirPath = stateRoot.resolve("skill-validations").toAbsolutePath().normalize().toString(),
    )
    override val fileSystem: SandboxFileSystem = LocalSandboxFileSystem(
        settingsProvider = settingsProvider,
        runtimePaths = runtimePaths,
    )
    override val commandExecutor: SandboxCommandExecutor = LocalSandboxCommandExecutor(
        fileSystem = fileSystem,
    )
}
