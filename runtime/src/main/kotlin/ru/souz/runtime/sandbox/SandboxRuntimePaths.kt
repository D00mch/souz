package ru.souz.runtime.sandbox

data class SandboxRuntimePaths(
    val homePath: String,
    val workspaceRootPath: String?,
    val stateRootPath: String,
    val sessionsDirPath: String,
    val vectorIndexDirPath: String,
    val logsDirPath: String,
    val modelsDirPath: String,
    val nativeLibsDirPath: String,
    val skillsDirPath: String,
    val skillValidationsDirPath: String,
)
