package ru.souz.agent.skills.implementations.bundle

import java.nio.file.Files
import java.nio.file.Path
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.bundle.SkillPathNormalizer
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

        return SkillBundle.Companion.fromFiles(skillId = skillId, files = files)
    }
}
