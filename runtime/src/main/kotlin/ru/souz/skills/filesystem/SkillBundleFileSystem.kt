package ru.souz.skills.filesystem

import java.nio.file.Path

/**
 * Request-scoped context passed to filesystem operations while loading a skill
 * bundle for a particular user.
 */
data class SkillBundleFsContext(
    val userId: String,
)

/**
 * Minimal filesystem contract used by skill bundle loading.
 *
 * Implementations are responsible for enforcing the runtime safety rules around
 * path resolution, directory traversal, and text file access for a concrete
 * host environment.
 */
interface SkillBundleFileSystem {
    /**
     * Resolves a user-provided bundle root into a canonical directory that is
     * allowed for the current [context].
     *
     * Implementations should reject blank paths, non-directories, symbolic-link
     * roots, and any location outside the allowed filesystem boundary.
     */
    suspend fun resolveSafeDirectory(
        context: SkillBundleFsContext,
        rawPath: String,
    ): Path

    /**
     * Returns every regular file reachable from [root] after applying the same
     * path-safety checks that protect bundle loading.
     *
     * Implementations should reject unsupported entry types and symbolic links
     * instead of silently skipping them.
     */
    suspend fun listRegularFiles(
        context: SkillBundleFsContext,
        root: Path,
    ): List<Path>

    /**
     * Reads [path] as strict UTF-8 text after validating it for the current
     * [context] and enforcing [maxBytes].
     *
     * Implementations should fail for binary content, invalid UTF-8, unsafe
     * paths, and files that exceed the configured size limit.
     */
    suspend fun readUtf8File(
        context: SkillBundleFsContext,
        path: Path,
        maxBytes: Long,
    ): String
}
