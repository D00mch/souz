package ru.souz.backend.storage.postgres

import java.sql.Connection
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    override suspend fun load(): CodexOAuthCredentials? {
        val stored = dataSource.read(::readStoredEncryptedPayload)
        return decodeStoredCredentials(stored)
    }

    override suspend fun compareAndSet(
        expectedVersion: Long?,
        credentials: CodexOAuthCredentials,
    ): Boolean {
        require(credentials.version == nextVersion(expectedVersion)) {
            "Codex credential version must advance exactly once from the expected version."
        }
        return saveIfVersionMatches(expectedVersion, credentials)
    }

    override suspend fun <T> withRefreshLease(
        action: suspend (leasedStore: CodexOAuthCredentialStore) -> T,
    ): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                acquireRefreshLease(connection)
                val result = action(ConnectionCredentialStore(connection))
                connection.commit()
                result
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    /** Consumes the configured deployment seed at most once across all backend processes. */
    suspend fun bootstrapInitialCredentials() {
        val seed = initialSeed ?: return
        dataSource.write { connection ->
            val claimedBootstrap = connection.prepareStatement(
                """
                insert into backend_codex_oauth_bootstrap(singleton)
                values (true)
                on conflict (singleton) do nothing
                """.trimIndent()
            ).use { statement ->
                statement.executeUpdate() == 1
            }
            if (claimedBootstrap) {
                val credentials = CodexOAuthCredentials(
                    accessToken = seed.accessToken,
                    refreshToken = seed.refreshToken,
                    accountId = seed.accountId,
                    expiresAtEpochSeconds = seed.expiresAtEpochSeconds,
                    version = 0L,
                )
                val encryptedPayload = encodeCredentials(credentials)
                insertCredentialsIfAbsent(connection, encryptedPayload, credentials.version)
            }
        }
    }

    private fun readStoredEncryptedPayload(connection: Connection): StoredEncryptedPayload? =
        connection.prepareStatement(
            "select encrypted_payload, version from backend_codex_oauth_credentials where singleton = true"
        ).use { statement ->
            statement.executeQuery().use result@{ resultSet ->
                if (!resultSet.next()) return@result null
                StoredEncryptedPayload(
                    encryptedPayload = resultSet.getBytes("encrypted_payload").toString(Charsets.UTF_8),
                    version = resultSet.getLong("version"),
                )
            }
        }

    private suspend fun decodeStoredCredentials(
        stored: StoredEncryptedPayload?,
    ): CodexOAuthCredentials? = decryptedPayloadMutex.withLock {
        if (stored == null) {
            decryptedPayloadCache = null
            return@withLock null
        }

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
        connection: Connection? = null,
    ): Boolean {
        val encryptedPayload = encodeCredentials(credentials)
        val save: (Connection) -> Boolean = { targetConnection ->
            if (expectedVersion == null) {
                insertCredentialsIfAbsent(targetConnection, encryptedPayload, credentials.version)
            } else {
                targetConnection.prepareStatement(
                    """
                    update backend_codex_oauth_credentials
                    set encrypted_payload = ?, version = ?, updated_at = now()
                    where singleton = true and version = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setBytes(1, encryptedPayload.toByteArray(Charsets.UTF_8))
                    statement.setLong(2, credentials.version)
                    statement.setLong(3, expectedVersion)
                    statement.executeUpdate() == 1
                }
            }
        }
        val saved = if (connection == null) dataSource.write(save) else save(connection)
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

    private fun encodeCredentials(credentials: CodexOAuthCredentials): String {
        require(credentials.isCompleteRotatingSet) {
            "Backend Codex credentials must include refresh token, account ID, and expiry."
        }
        val payload = StoredCodexOAuthPayload(
            accessToken = credentials.accessToken,
            refreshToken = requireNotNull(credentials.refreshToken),
            accountId = requireNotNull(credentials.accountId),
            expiresAtEpochSeconds = requireNotNull(credentials.expiresAtEpochSeconds),
        )
        return AesGcmSecretCodec.encrypt(
            masterKey = masterKey,
            plainText = postgresStorageMapper.writeValueAsString(payload),
        )
    }

    private fun insertCredentialsIfAbsent(
        connection: Connection,
        encryptedPayload: String,
        version: Long,
    ): Boolean = connection.prepareStatement(
        """
        insert into backend_codex_oauth_credentials(singleton, encrypted_payload, version)
        values (true, ?, ?)
        on conflict (singleton) do nothing
        """.trimIndent()
    ).use { statement ->
        statement.setBytes(1, encryptedPayload.toByteArray(Charsets.UTF_8))
        statement.setLong(2, version)
        statement.executeUpdate() == 1
    }

    private fun acquireRefreshLease(connection: Connection) {
        connection.prepareStatement(
            "select pg_advisory_xact_lock(hashtext(?), hashtext(?))"
        ).use { statement ->
            statement.setString(1, REFRESH_LEASE_NAMESPACE)
            statement.setString(2, REFRESH_LEASE_NAME)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "PostgreSQL did not acquire the Codex OAuth refresh lease." }
            }
        }
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

    private inner class ConnectionCredentialStore(
        private val connection: Connection,
    ) : CodexOAuthCredentialStore {
        override suspend fun load(): CodexOAuthCredentials? =
            decodeStoredCredentials(readStoredEncryptedPayload(connection))

        override suspend fun compareAndSet(
            expectedVersion: Long?,
            credentials: CodexOAuthCredentials,
        ): Boolean {
            require(credentials.version == nextVersion(expectedVersion)) {
                "Codex credential version must advance exactly once from the expected version."
            }
            return saveIfVersionMatches(expectedVersion, credentials, connection)
        }
    }

    private companion object {
        const val REFRESH_LEASE_NAMESPACE = "souz.backend"
        const val REFRESH_LEASE_NAME = "codex_oauth_refresh"
    }
}
