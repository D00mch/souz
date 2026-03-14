package ru.souz.permissions

import java.io.File

object MacAppEnvironment {
    private const val SANDBOX_HOME_MARKER = "/Library/Containers/"
    private val osName: String = System.getProperty("os.name", "")
    private val processHomeCandidates: List<String>
        get() = listOf(System.getProperty("user.home"), System.getenv("HOME"))
            .map { it?.trim().orEmpty() }
            .filter { it.isNotBlank() }

    private val processHome: String
        get() = processHomeCandidates.firstOrNull().orEmpty()

    val isMac: Boolean = osName.contains("mac", ignoreCase = true)

    val isSandboxed: Boolean by lazy {
        if (!isMac) return@lazy false
        val sandboxContainerId = System.getenv("APP_SANDBOX_CONTAINER_ID")
        if (!sandboxContainerId.isNullOrBlank()) return@lazy true

        processHomeCandidates.any { it.contains(SANDBOX_HOME_MARKER) }
    }

    /**
     * Filesystem root for app-private data (config/cache/db/logs).
     * In sandbox this should stay inside the container.
     */
    val appDataHome: String by lazy {
        processHome.ifBlank { resolveRealHomeFromUserName().orEmpty() }
    }

    val userHomeForUserFacingPaths: String by lazy {
        if (!isSandboxed) return@lazy appDataHome
        processHomeCandidates.firstNotNullOfOrNull { resolveRealHomeFromSandboxPath(it) }
            ?: resolveRealHomeFromUserName()
            ?: appDataHome
    }

    private fun resolveRealHomeFromSandboxPath(path: String): String? {
        val markerIndex = path.indexOf(SANDBOX_HOME_MARKER)
        if (markerIndex <= 0) return null
        val candidate = path.substring(0, markerIndex)
        return candidate.takeIf { File(it).isDirectory }
    }

    private fun resolveRealHomeFromUserName(): String? {
        val user = System.getProperty("user.name").orEmpty().trim()
        if (user.isBlank()) return null
        val candidate = "/Users/$user"
        return candidate.takeIf { File(it).isDirectory }
    }
}
