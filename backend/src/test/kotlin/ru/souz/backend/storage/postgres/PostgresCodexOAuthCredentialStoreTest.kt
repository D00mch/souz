package ru.souz.backend.storage.postgres

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import ru.souz.backend.app.BackendCodexOAuthSeed
import ru.souz.db.AesGcmSecretCodec
import ru.souz.llms.codex.CodexOAuthCredentials

class PostgresCodexOAuthCredentialStoreTest {
    @Test
    fun `separate backend stores share encrypted seeded credentials`() = runTest {
        val schema = newPostgresSchema("postgres_codex_credentials")
        val postgresConfig = postgresAppConfig(schema).postgres
        val seed = BackendCodexOAuthSeed(
            accessToken = "seed-access-token",
            refreshToken = "seed-refresh-token",
            accountId = "seed-account-id",
            expiresAtEpochSeconds = 1_900_000_000L,
        )

        PostgresDataSourceFactory.create(postgresConfig).use { firstPodDataSource ->
            PostgresDataSourceFactory.create(postgresConfig).use { secondPodDataSource ->
                val firstPod = PostgresCodexOAuthCredentialStore(firstPodDataSource, MASTER_KEY, seed)
                val secondPod = PostgresCodexOAuthCredentialStore(secondPodDataSource, MASTER_KEY)

                assertEquals("seed-access-token", firstPod.load()?.accessToken)
                assertEquals(firstPod.load(), secondPod.load())

                val rawPayload = rawPayload(secondPodDataSource)
                assertTrue(rawPayload.startsWith("enc:v1:"))
                assertFalse(rawPayload.contains(seed.accessToken))
                assertFalse(rawPayload.contains(seed.refreshToken))
                assertFalse(rawPayload.contains(seed.accountId))
            }
        }
    }

    @Test
    fun `concurrent pod seeds converge on the database winner`() = runTest {
        val schema = newPostgresSchema("postgres_codex_seed_race")
        val postgresConfig = postgresAppConfig(schema).postgres
        val seedA = BackendCodexOAuthSeed("access-a", "refresh-a", "account-a", 1_900_000_001L)
        val seedB = BackendCodexOAuthSeed("access-b", "refresh-b", "account-b", 1_900_000_002L)

        PostgresDataSourceFactory.create(postgresConfig).use { firstPodDataSource ->
            PostgresDataSourceFactory.create(postgresConfig).use { secondPodDataSource ->
                val firstPod = PostgresCodexOAuthCredentialStore(firstPodDataSource, MASTER_KEY, seedA)
                val secondPod = PostgresCodexOAuthCredentialStore(secondPodDataSource, MASTER_KEY, seedB)

                val loaded = coroutineScope {
                    listOf(
                        async { firstPod.load() },
                        async { secondPod.load() },
                    ).awaitAll()
                }

                val winner = assertNotNull(loaded.first())
                assertEquals(winner, loaded[1])
                assertEquals(winner, firstPod.load())
                assertEquals(winner, secondPod.load())
                assertTrue(winner.accessToken in setOf(seedA.accessToken, seedB.accessToken))
            }
        }
    }

    @Test
    fun `concurrent refresh compare and set cannot replace winner with stale credentials`() = runTest {
        val schema = newPostgresSchema("postgres_codex_cas")
        val postgresConfig = postgresAppConfig(schema).postgres
        val seed = BackendCodexOAuthSeed("access-0", "refresh-0", "account", 1_800_000_000L)

        PostgresDataSourceFactory.create(postgresConfig).use { firstPodDataSource ->
            PostgresDataSourceFactory.create(postgresConfig).use { secondPodDataSource ->
                val firstPod = PostgresCodexOAuthCredentialStore(firstPodDataSource, MASTER_KEY, seed)
                val secondPod = PostgresCodexOAuthCredentialStore(secondPodDataSource, MASTER_KEY, seed)
                val firstRead = assertNotNull(firstPod.load())
                val secondRead = assertNotNull(secondPod.load())
                assertEquals(firstRead.version, secondRead.version)

                val candidates = listOf(
                    firstPod to refreshed(firstRead, "access-a", "refresh-a", 1_900_000_001L),
                    secondPod to refreshed(secondRead, "access-b", "refresh-b", 1_900_000_002L),
                )
                val outcomes = coroutineScope {
                    candidates.map { (store, candidate) ->
                        async { store.compareAndSet(firstRead.version, candidate) }
                    }.awaitAll()
                }

                assertEquals(1, outcomes.count { it })
                val winnerIndex = outcomes.indexOf(true)
                val winner = candidates[winnerIndex].second
                assertEquals(winner, firstPod.load())
                assertEquals(winner, secondPod.load())

                val payloadBeforeStaleWrite = rawPayload(firstPodDataSource)
                val stale = refreshed(firstRead, "stale-access", "stale-refresh", 2_000_000_000L)
                assertFalse(secondPod.compareAndSet(firstRead.version, stale))
                assertEquals(payloadBeforeStaleWrite, rawPayload(secondPodDataSource))
                assertEquals(winner, secondPod.load())
            }
        }
    }

    @Test
    fun `unchanged encrypted row is decrypted once per pod`() = runTest {
        val schema = newPostgresSchema("postgres_codex_decryption_cache")
        val postgresConfig = postgresAppConfig(schema).postgres
        val seed = BackendCodexOAuthSeed("access-0", "refresh-0", "account", 1_900_000_000L)
        var decryptions = 0

        PostgresDataSourceFactory.create(postgresConfig).use { writerDataSource ->
            val writer = PostgresCodexOAuthCredentialStore(writerDataSource, MASTER_KEY, seed)
            val expected = assertNotNull(writer.load())

            PostgresDataSourceFactory.create(postgresConfig).use { readerDataSource ->
                val reader = PostgresCodexOAuthCredentialStore(
                    dataSource = readerDataSource,
                    masterKey = MASTER_KEY,
                    decryptPayload = { masterKey, payload ->
                        decryptions += 1
                        AesGcmSecretCodec.decrypt(masterKey, payload)
                    },
                )

                assertEquals(expected, reader.load())
                assertEquals(expected, reader.load())
                assertEquals(expected, reader.load())
                assertEquals(1, decryptions)
            }
        }
    }

    @Test
    fun `deleted row never leaks a cached credential and configured seed restores an empty store`() = runTest {
        val schema = newPostgresSchema("postgres_codex_delete")
        val postgresConfig = postgresAppConfig(schema).postgres
        val credentials = CodexOAuthCredentials("access", "refresh", "account", 1_900_000_000L, 0L)

        PostgresDataSourceFactory.create(postgresConfig).use { dataSource ->
            val unseededStore = PostgresCodexOAuthCredentialStore(dataSource, MASTER_KEY)
            assertTrue(unseededStore.compareAndSet(null, credentials))
            assertEquals(credentials, unseededStore.load())

            deleteCredentialRow(dataSource)
            assertNull(unseededStore.load())

            val seed = BackendCodexOAuthSeed("seed-access", "seed-refresh", "seed-account", 1_900_000_001L)
            val seededStore = PostgresCodexOAuthCredentialStore(dataSource, MASTER_KEY, seed)
            assertEquals(seed.accessToken, seededStore.load()?.accessToken)
            deleteCredentialRow(dataSource)
            assertEquals(seed.accessToken, seededStore.load()?.accessToken)
        }
    }

    @Test
    fun `wrong master key fails closed without exposing encrypted payload`() = runTest {
        val schema = newPostgresSchema("postgres_codex_wrong_master_key")
        val postgresConfig = postgresAppConfig(schema).postgres
        val seed = BackendCodexOAuthSeed("access", "refresh", "account", 1_900_000_000L)

        PostgresDataSourceFactory.create(postgresConfig).use { writerDataSource ->
            assertNotNull(PostgresCodexOAuthCredentialStore(writerDataSource, MASTER_KEY, seed).load())

            PostgresDataSourceFactory.create(postgresConfig).use { readerDataSource ->
                val error = assertFailsWith<IllegalStateException> {
                    PostgresCodexOAuthCredentialStore(readerDataSource, "wrong-master-key").load()
                }

                assertEquals("Stored Codex OAuth credentials are unreadable.", error.message)
                assertFalse(error.message.orEmpty().contains(rawPayload(readerDataSource)))
            }
        }
    }

    private suspend fun rawPayload(dataSource: javax.sql.DataSource): String =
        dataSource.read { connection ->
            connection.prepareStatement(
                "select encrypted_payload from backend_mutable_credentials where credential_key = 'codex_oauth'"
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getBytes(1).toString(Charsets.UTF_8)
                }
            }
        }

    private suspend fun deleteCredentialRow(dataSource: javax.sql.DataSource) {
        dataSource.write { connection ->
            connection.prepareStatement(
                "delete from backend_mutable_credentials where credential_key = 'codex_oauth'"
            ).use { statement ->
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun refreshed(
        previous: CodexOAuthCredentials,
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
    ): CodexOAuthCredentials = previous.copy(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochSeconds = expiresAt,
        version = previous.version + 1L,
    )

    private companion object {
        const val MASTER_KEY = "test-master-key"
    }
}
