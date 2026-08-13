package ru.souz.backend.storage.postgres

import javax.sql.DataSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.app.BackendCodexOAuthSeed
import ru.souz.db.AesGcmSecretCodec
import ru.souz.llms.codex.CodexOAuthCredentialStore
import ru.souz.llms.codex.CodexOAuthCredentials

/** PostgreSQL source of truth for the backend-wide rotating Codex OAuth session. */
internal class PostgresCodexOAuthCredentialStore(
    private val dataSource: DataSource,
    private val masterKey: String,
    private val initialSeed: BackendCodexOAuthSeed? = null,
    private val decryptPayload: (String, String) -> String = AesGcmSecretCodec::decrypt,
) : CodexOAuthCredentialStore {
    private val decryptedPayloadMutex = Mutex()
    private var decryptedPayloadCache: DecryptedPayloadCache? = null

    override suspend fun load(): CodexOAuthCredentials? =
        loadStoredCredentials() ?: seedInitialCredentials()

    override suspend fun compareAndSet(
        expectedVersion: Long?,
        credentials: CodexOAuthCredentials,
    ): Boolean {
        require(credentials.version == nextVersion(expectedVersion)) {
            "Codex credential version must advance exactly once from the expected version."
        }
        if (expectedVersion == null && initialSeed != null && load() != null) {
            return false
        }
        return saveIfVersionMatches(expectedVersion, credentials)
    }

    private suspend fun seedInitialCredentials(): CodexOAuthCredentials? {
        val seed = initialSeed ?: return null
        val credentials = CodexOAuthCredentials(
            accessToken = seed.accessToken,
            refreshToken = seed.refreshToken,
            accountId = seed.accountId,
            expiresAtEpochSeconds = seed.expiresAtEpochSeconds,
            version = 0L,
        )
        return if (saveIfVersionMatches(expectedVersion = null, credentials)) {
            credentials
        } else {
            loadStoredCredentials()
        }
    }

    private suspend fun loadStoredCredentials(): CodexOAuthCredentials? {
        val stored = dataSource.read { connection ->
            connection.prepareStatement(
                "select encrypted_payload, version from backend_mutable_credentials where credential_key = ?"
            ).use { statement ->
                statement.setString(1, CODEX_CREDENTIAL_KEY)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) return@read null
                    StoredEncryptedPayload(
                        encryptedPayload = resultSet.getBytes("encrypted_payload").toString(Charsets.UTF_8),
                        version = resultSet.getLong("version"),
                    )
                }
            }
        } ?: return null
        return decryptedPayloadMutex.withLock {
            val cached = decryptedPayloadCache
            if (
                cached != null &&
                cached.version == stored.version &&
                cached.encryptedPayload == stored.encryptedPayload
            ) {
                return@withLock cached.credentials
            }

            val credentials = decodeCredentials(stored)
            decryptedPayloadCache = DecryptedPayloadCache(
                encryptedPayload = stored.encryptedPayload,
                version = stored.version,
                credentials = credentials,
            )
            credentials
        }
    }

    private fun decodeCredentials(stored: StoredEncryptedPayload): CodexOAuthCredentials = try {
        val payload = decryptPayload(masterKey, stored.encryptedPayload)
        val decoded: StoredCodexOAuthPayload = postgresStorageMapper.readValue(
            payload,
            StoredCodexOAuthPayload::class.java,
        )
        CodexOAuthCredentials(
            accessToken = decoded.accessToken,
            refreshToken = decoded.refreshToken,
            accountId = decoded.accountId,
            expiresAtEpochSeconds = decoded.expiresAtEpochSeconds,
            version = stored.version,
        )
    } catch (error: Exception) {
        throw IllegalStateException("Stored Codex OAuth credentials are unreadable.", error)
    }

    private suspend fun saveIfVersionMatches(
        expectedVersion: Long?,
        credentials: CodexOAuthCredentials,
    ): Boolean {
        require(credentials.isCompleteRotatingSet) {
            "Backend Codex credentials must include refresh token, account ID, and expiry."
        }
        val payload = StoredCodexOAuthPayload(
            accessToken = credentials.accessToken,
            refreshToken = requireNotNull(credentials.refreshToken),
            accountId = requireNotNull(credentials.accountId),
            expiresAtEpochSeconds = requireNotNull(credentials.expiresAtEpochSeconds),
        )
        val encryptedPayload = AesGcmSecretCodec.encrypt(
            masterKey = masterKey,
            plainText = postgresStorageMapper.writeValueAsString(payload),
        )
        val saved = dataSource.write { connection ->
            if (expectedVersion == null) {
                connection.prepareStatement(
                    """
                    insert into backend_mutable_credentials(credential_key, encrypted_payload, version)
                    values (?, ?, ?)
                    on conflict (credential_key) do nothing
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, CODEX_CREDENTIAL_KEY)
                    statement.setBytes(2, encryptedPayload.toByteArray(Charsets.UTF_8))
                    statement.setLong(3, credentials.version)
                    statement.executeUpdate() == 1
                }
            } else {
                connection.prepareStatement(
                    """
                    update backend_mutable_credentials
                    set encrypted_payload = ?, version = ?, updated_at = now()
                    where credential_key = ? and version = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setBytes(1, encryptedPayload.toByteArray(Charsets.UTF_8))
                    statement.setLong(2, credentials.version)
                    statement.setString(3, CODEX_CREDENTIAL_KEY)
                    statement.setLong(4, expectedVersion)
                    statement.executeUpdate() == 1
                }
            }
        }
        if (saved) {
            decryptedPayloadMutex.withLock {
                decryptedPayloadCache = DecryptedPayloadCache(
                    encryptedPayload = encryptedPayload,
                    version = credentials.version,
                    credentials = credentials,
                )
            }
        }
        return saved
    }

    private fun nextVersion(expectedVersion: Long?): Long = expectedVersion?.plus(1L) ?: 0L

    private data class StoredCodexOAuthPayload(
        val accessToken: String,
        val refreshToken: String,
        val accountId: String,
        val expiresAtEpochSeconds: Long,
    )

    private data class StoredEncryptedPayload(
        val encryptedPayload: String,
        val version: Long,
    )

    private data class DecryptedPayloadCache(
        val encryptedPayload: String,
        val version: Long,
        val credentials: CodexOAuthCredentials,
    )

    private companion object {
        const val CODEX_CREDENTIAL_KEY = "codex_oauth"
    }
}
