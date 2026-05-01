package ru.souz.agent.skills

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.streams.asSequence

class SkillBundleLoader {

    fun loadDirectory(
        skillId: SkillId,
        rootDirectory: Path,
    ): SkillBundle {
        if (!rootDirectory.isDirectory()) {
            throw SkillBundleException("Skill root is not a directory: $rootDirectory")
        }

        val files = Files.walk(rootDirectory).use { stream ->
            stream.asSequence()
                .filter { path -> Files.isRegularFile(path) }
                .map { path ->
                    val relativePath = rootDirectory.relativize(path).toString()
                    SkillFile(
                        normalizedPath = SkillPathNormalizer.normalize(relativePath),
                        content = Files.readAllBytes(path),
                    )
                }
                .toList()
        }

        return SkillBundle.fromFiles(skillId = skillId, files = files)
    }
}
