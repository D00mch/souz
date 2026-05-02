package ru.souz.skills.bundle

import java.util.Locale
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.bundle.SkillPathNormalizer
import ru.souz.agent.skills.validation.SkillValidationPolicy
import ru.souz.skills.filesystem.SkillBundleFileSystem
import ru.souz.skills.filesystem.SkillBundleFsContext

class FileSystemSkillBundleLoader(
    private val fileSystem: SkillBundleFileSystem,
    private val maxFiles: Int = 64,
    private val allowedExtensions: Set<String> = DEFAULT_ALLOWED_EXTENSIONS,
) {
    suspend fun loadDirectory(
        context: SkillBundleFsContext,
        skillId: SkillId,
        rawRoot: String,
        policy: SkillValidationPolicy = SkillValidationPolicy.default(),
    ): SkillBundle {
        require(maxFiles > 0) { "maxFiles must be positive." }

        val root = fileSystem.resolveSafeDirectory(context, rawRoot)
        val paths = fileSystem.listRegularFiles(context, root)
        if (paths.size > maxFiles) {
            throw SkillBundleException("Too many files in skill bundle: ${paths.size}. Max allowed: $maxFiles")
        }

        var totalBytes = 0L
        val files = paths.map { path ->
            val relativePath = root.relativize(path).toString().replace('\\', '/')
            val normalizedPath = SkillPathNormalizer.normalize(relativePath)
            requireAllowedExtension(normalizedPath)

            val content = fileSystem.readUtf8File(
                context = context,
                path = path,
                maxBytes = policy.maxFileBytes.toLong(),
            ).toByteArray(Charsets.UTF_8)

            totalBytes += content.size.toLong()
            if (totalBytes > policy.maxBundleBytes.toLong()) {
                throw SkillBundleException(
                    "Skill bundle exceeds the max allowed size of ${policy.maxBundleBytes} bytes: $rawRoot"
                )
            }

            SkillFile(
                normalizedPath = normalizedPath,
                content = content,
            )
        }

        return SkillBundle.fromFiles(
            skillId = skillId,
            files = files,
        )
    }

    private fun requireAllowedExtension(normalizedPath: String) {
        val extension = normalizedPath.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        if (extension !in allowedExtensions) {
            throw SkillBundleException("Disallowed skill file extension for $normalizedPath")
        }
    }

    companion object {
        val DEFAULT_ALLOWED_EXTENSIONS: Set<String> = setOf(
            "cjs",
            "css",
            "csv",
            "html",
            "js",
            "json",
            "jsx",
            "md",
            "mjs",
            "py",
            "sh",
            "sql",
            "text",
            "toml",
            "ts",
            "tsx",
            "txt",
            "xml",
            "yaml",
            "yml",
            "zsh",
        )
    }
}
