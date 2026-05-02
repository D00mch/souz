package ru.souz.skills.filesystem

import java.nio.file.Path

data class SkillBundleFsContext(
    val userId: String,
)

interface SkillBundleFileSystem {
    suspend fun resolveSafeDirectory(
        context: SkillBundleFsContext,
        rawPath: String,
    ): Path

    suspend fun listRegularFiles(
        context: SkillBundleFsContext,
        root: Path,
    ): List<Path>

    suspend fun readUtf8File(
        context: SkillBundleFsContext,
        path: Path,
        maxBytes: Long,
    ): String
}
