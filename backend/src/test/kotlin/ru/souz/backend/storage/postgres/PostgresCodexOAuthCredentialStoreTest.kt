package ru.souz.backend.storage.postgres

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.souz.backend.app.BackendCodexOAuthSeed
import ru.souz.db.AesGcmSecretCodec
import ru.souz.llms.codex.CodexOAuthCredentials
import ru.souz.llms.codex.CodexOAuthService

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
                firstPod.bootstrapInitialCredentials()

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

                coroutineScope {
                    listOf(
                        async { firstPod.bootstrapInitialCredentials() },
                        async { secondPod.bootstrapInitialCredentials() },
                    ).awaitAll()
                }
                val loaded = listOf(firstPod.load(), secondPod.load())

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
                firstPod.bootstrapInitialCredentials()
                secondPod.bootstrapInitialCredentials()
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
    fun `concurrent backend services acquire the database lease before refreshing upstream`() = runTest {
        val schema = newPostgresSchema("postgres_codex_refresh_lease")
        val postgresConfig = postgresAppConfig(schema).postgres
        val seed = BackendCodexOAuthSeed("access-0", "refresh-0", "account", 1L)
        val firstRefreshEntered = CompletableDeferred<Unit>()
        val releaseFirstRefresh = CompletableDeferred<Unit>()
        val refreshCountMutex = Mutex()
        var upstreamRefreshCount = 0
        val httpClient = HttpClient(MockEngine) {
            install(HttpTimeout)
            engine {
                addHandler {
                    val refreshNumber = refreshCountMutex.withLock {
                        upstreamRefreshCount += 1
                        upstreamRefreshCount
                    }
                    if (refreshNumber == 1) {
                        firstRefreshEntered.complete(Unit)
                        releaseFirstRefresh.await()
                    }
                    respond(
                        content = """{"access_token":"access-$refreshNumber","refresh_token":"refresh-$refreshNumber","expires_in":3600}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        try {
            PostgresDataSourceFactory.create(postgresConfig).use { firstPodDataSource ->
                PostgresDataSourceFactory.create(postgresConfig).use { secondPodDataSource ->
                    val firstStore = PostgresCodexOAuthCredentialStore(firstPodDataSource, MASTER_KEY, seed)
                    val secondStore = PostgresCodexOAuthCredentialStore(secondPodDataSource, MASTER_KEY, seed)
                    firstStore.bootstrapInitialCredentials()
                    secondStore.bootstrapInitialCredentials()
                    val firstService = CodexOAuthService(firstStore, httpClient)
                    val secondService = CodexOAuthService(secondStore, httpClient)

                    val firstResult = async(Dispatchers.Default) { firstService.refreshTokenIfNeeded() }
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) { firstRefreshEntered.await() }
                    }
                    val secondResult = async(Dispatchers.Default) { secondService.refreshTokenIfNeeded() }
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) { awaitBlockedRefreshLease(secondPodDataSource) }
                    }

                    assertEquals(1, refreshCountMutex.withLock { upstreamRefreshCount })
                    assertFalse(secondResult.isCompleted)

                    releaseFirstRefresh.complete(Unit)
                    val results = awaitAll(firstResult, secondResult)

                    assertEquals(1, refreshCountMutex.withLock { upstreamRefreshCount })
                    assertEquals(results[0], results[1])
                    assertEquals("access-1", results[0].accessToken)
                    assertEquals(1L, results[0].version)
                }
            }
        } finally {
            releaseFirstRefresh.complete(Unit)
            httpClient.close()
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
            writer.bootstrapInitialCredentials()
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
    fun `deployment seed is consumed once and deletion cannot resurrect it`() = runTest {
        val schema = newPostgresSchema("postgres_codex_delete")
        val postgresConfig = postgresAppConfig(schema).postgres
        val seed = BackendCodexOAuthSeed("seed-access", "seed-refresh", "seed-account", 1_900_000_001L)

        PostgresDataSourceFactory.create(postgresConfig).use { dataSource ->
            val firstProcess = PostgresCodexOAuthCredentialStore(dataSource, MASTER_KEY, seed)
            firstProcess.bootstrapInitialCredentials()
            assertEquals(seed.accessToken, firstProcess.load()?.accessToken)

            deleteCredentialRow(dataSource)
            assertNull(firstProcess.load())

            val restartedProcess = PostgresCodexOAuthCredentialStore(dataSource, MASTER_KEY, seed)
            assertNull(restartedProcess.load())
            restartedProcess.bootstrapInitialCredentials()
            assertNull(restartedProcess.load())
            assertEquals(1, bootstrapMarkerCount(dataSource))
        }
    }

    @Test
    fun `normal load never consumes a configured seed`() = runTest {
        val schema = newPostgresSchema("postgres_codex_load_without_bootstrap")
        val postgresConfig = postgresAppConfig(schema).postgres
        val seed = BackendCodexOAuthSeed("seed-access", "seed-refresh", "seed-account", 1_900_000_001L)

        PostgresDataSourceFactory.create(postgresConfig).use { dataSource ->
            val store = PostgresCodexOAuthCredentialStore(dataSource, MASTER_KEY, seed)

            assertNull(store.load())
            assertEquals(0, bootstrapMarkerCount(dataSource))

            store.bootstrapInitialCredentials()
            assertEquals(seed.accessToken, store.load()?.accessToken)
        }
    }

    @Test
    fun `wrong master key fails closed without exposing encrypted payload`() = runTest {
        val schema = newPostgresSchema("postgres_codex_wrong_master_key")
        val postgresConfig = postgresAppConfig(schema).postgres
        val seed = BackendCodexOAuthSeed("access", "refresh", "account", 1_900_000_000L)

        PostgresDataSourceFactory.create(postgresConfig).use { writerDataSource ->
            val writer = PostgresCodexOAuthCredentialStore(writerDataSource, MASTER_KEY, seed)
            writer.bootstrapInitialCredentials()
            assertNotNull(writer.load())

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
                "select encrypted_payload from backend_codex_oauth_credentials where singleton = true"
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
                "delete from backend_codex_oauth_credentials where singleton = true"
            ).use { statement ->
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private suspend fun bootstrapMarkerCount(dataSource: javax.sql.DataSource): Int =
        dataSource.read { connection ->
            connection.prepareStatement(
                "select count(*) from backend_codex_oauth_bootstrap where singleton = true"
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private suspend fun awaitBlockedRefreshLease(dataSource: javax.sql.DataSource) {
        while (!hasBlockedRefreshLease(dataSource)) {
            delay(10)
        }
    }

    private suspend fun hasBlockedRefreshLease(dataSource: javax.sql.DataSource): Boolean =
        dataSource.read { connection ->
            connection.prepareStatement(
                """
                select exists(
                  select 1
                  from pg_locks
                  where locktype = 'advisory'
                    and classid = hashtext(?)::oid
                    and objid = hashtext(?)::oid
                    and not granted
                )
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, "souz.backend")
                statement.setString(2, "codex_oauth_refresh")
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getBoolean(1)
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
