package ru.souz.agent.skills.bundle

object SkillPathNormalizer {
    fun normalize(rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) throw SkillBundleException("Skill path must not be blank.")
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(trimmed)) {
            throw SkillBundleException("Absolute skill paths are not allowed: $rawPath")
        }
        if (trimmed.contains('\\')) {
            throw SkillBundleException("Backslash separators are not allowed in skill paths: $rawPath")
        }

        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw SkillBundleException("Skill path must not be empty: $rawPath")
        if (segments.any { it == "." || it == ".." }) {
            throw SkillBundleException("Path traversal is not allowed: $rawPath")
        }
        if (segments.any { it.contains('\u0000') }) {
            throw SkillBundleException("Null bytes are not allowed in skill paths: $rawPath")
        }

        return segments.joinToString("/")
    }
}
