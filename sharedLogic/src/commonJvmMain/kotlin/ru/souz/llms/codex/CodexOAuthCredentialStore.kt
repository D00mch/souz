package ru.souz.llms.codex

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.db.SettingsProvider

/** Codex access state. Desktop settings may contain a non-rotating access token only. */
data class CodexOAuthCredentials(
    val accessToken: String,
    val refreshToken: String? = null,
    val accountId: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val version: Long,
) {
    init {
        require(accessToken.isNotBlank()) { "Codex access token must not be blank." }
        require(refreshToken == null || refreshToken.isNotBlank()) {
            "Codex refresh token must be null or nonblank."
        }
        require(accountId == null || accountId.isNotBlank()) {
            "Codex account ID must be null or nonblank."
        }
        require(expiresAtEpochSeconds == null || expiresAtEpochSeconds > 0L) {
            "Codex expiry must be null or positive."
        }
        require(version >= 0L) { "Codex credential version must not be negative." }
    }

    val isCompleteRotatingSet: Boolean
        get() = refreshToken != null && accountId != null && expiresAtEpochSeconds != null
}

/**
 * Storage boundary used by OAuth refresh. Compare-and-set prevents a stale refresh from replacing
 * credentials written by another process.
 */
interface CodexOAuthCredentialStore {
    suspend fun load(): CodexOAuthCredentials?

    suspend fun compareAndSet(
        expectedVersion: Long?,
        credentials: CodexOAuthCredentials,
    ): Boolean
}

/** Desktop adapter that keeps the existing SettingsProvider persistence behavior. */
class SettingsProviderCodexOAuthCredentialStore(
    private val settingsProvider: SettingsProvider,
) : CodexOAuthCredentialStore {
    private val mutex = Mutex()
    private var version = 0L

    override suspend fun load(): CodexOAuthCredentials? = mutex.withLock {
        settingsProvider.toCodexOAuthCredentials(version)
    }

    override suspend fun compareAndSet(
        expectedVersion: Long?,
        credentials: CodexOAuthCredentials,
    ): Boolean = mutex.withLock {
        val current = settingsProvider.toCodexOAuthCredentials(version)
        if (current?.version != expectedVersion) return@withLock false

        settingsProvider.codexAccessToken = credentials.accessToken
        settingsProvider.codexRefreshToken = credentials.refreshToken
        settingsProvider.codexAccountId = credentials.accountId
        settingsProvider.codexExpiresAt = credentials.expiresAtEpochSeconds
        version = credentials.version
        true
    }
}

private fun SettingsProvider.toCodexOAuthCredentials(version: Long): CodexOAuthCredentials? {
    val accessToken = codexAccessToken?.takeIf(String::isNotBlank) ?: return null
    return CodexOAuthCredentials(
        accessToken = accessToken,
        refreshToken = codexRefreshToken?.takeIf(String::isNotBlank),
        accountId = codexAccountId?.takeIf(String::isNotBlank),
        expiresAtEpochSeconds = codexExpiresAt,
        version = version,
    )
}
