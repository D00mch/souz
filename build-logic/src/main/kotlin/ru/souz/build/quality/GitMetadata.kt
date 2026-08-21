package ru.souz.build.quality

import java.io.File

internal object GitMetadata {
    private val shaPattern = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")
    private val repositoryRoutingVariables = setOf(
        "GIT_DIR",
        "GIT_WORK_TREE",
        "GIT_COMMON_DIR",
        "GIT_INDEX_FILE",
        "GIT_OBJECT_DIRECTORY",
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        "GIT_NAMESPACE",
    )

    fun read(repositoryDirectory: File): GitIdentity {
        val topLevel = File(runGit(repositoryDirectory, "rev-parse", "--show-toplevel")).canonicalFile
        check(topLevel == repositoryDirectory.canonicalFile) {
            "Git top-level does not match the quality-gate repository."
        }
        val testedCommit = validatedSha("tested commit", runGit(repositoryDirectory, "rev-parse", "HEAD"))
        val baseSha = optionalEnvironmentSha("SOUZ_PR_BASE_SHA")
        val headSha = optionalEnvironmentSha("SOUZ_PR_HEAD_SHA")
        val dirty = runGit(repositoryDirectory, "status", "--porcelain=v1", "--untracked-files=normal").isNotBlank()

        return GitIdentity(
            testedCommitSha = testedCommit,
            prBaseSha = baseSha,
            prHeadSha = headSha,
            dirtyWorktree = dirty,
        )
    }

    private fun optionalEnvironmentSha(name: String): String? {
        val value = System.getenv(name)?.trim().orEmpty()
        return value.ifBlank { null }?.let { validatedSha(name, it) }
    }

    private fun validatedSha(label: String, value: String): String {
        require(shaPattern.matches(value)) { "$label is not a full Git SHA." }
        return value.lowercase()
    }

    private fun runGit(repositoryDirectory: File, vararg arguments: String): String {
        val processBuilder = ProcessBuilder(listOf("git", "-C", repositoryDirectory.absolutePath) + arguments)
            .redirectErrorStream(true)
        repositoryRoutingVariables.forEach { processBuilder.environment().remove(it) }
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "git ${arguments.joinToString(" ")} failed with exit code $exitCode."
        }
        return output
    }
}
