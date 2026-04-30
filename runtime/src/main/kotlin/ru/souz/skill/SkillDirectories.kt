package ru.souz.skill

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

data class SkillDirectories(
    val workspaceSkillsDir: Path,
    val managedSkillsDir: Path,
) {
    val clawHubDirectory: Path
        get() = managedSkillsDir.resolve(".clawhub")

    val clawHubLockfile: Path
        get() = clawHubDirectory.resolve("lock.json")

    companion object {
        fun default(
            workspaceRoot: Path = detectWorkspaceRoot(),
            userHome: String = System.getProperty("user.home"),
        ): SkillDirectories = SkillDirectories(
            workspaceSkillsDir = workspaceRoot.resolve("skills"),
            managedSkillsDir = Path.of(userHome, ".local", "state", "souz", "skills"),
        )

        internal fun detectWorkspaceRoot(
            userDir: Path = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize(),
            workspaceOverride: String? = System.getProperty("souz.workspaceRoot")
                ?: System.getenv("SOUZ_WORKSPACE_ROOT"),
            codeSourcePath: Path? = runtimeCodeSourcePath(),
        ): Path {
            workspaceOverride
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { override -> return Path.of(override).toAbsolutePath().normalize() }

            return sequenceOf(userDir, codeSourcePath)
                .filterNotNull()
                .flatMap { start -> generateAncestors(start).asSequence() }
                .firstOrNull { candidate -> candidate.resolve("skills").exists() && candidate.resolve("skills").isDirectory() }
                ?: userDir
        }

        private fun generateAncestors(start: Path): List<Path> {
            val result = ArrayList<Path>()
            var current: Path? = start.toAbsolutePath().normalize()
            while (current != null) {
                result.add(current)
                current = current.parent
            }
            return result
        }

        private fun runtimeCodeSourcePath(): Path? = runCatching {
            val location = SkillDirectories::class.java.protectionDomain.codeSource?.location ?: return null
            val path = Path.of(URI(location.toString())).toAbsolutePath().normalize()
            if (Files.isRegularFile(path)) path.parent else path
        }.getOrNull()
    }
}
