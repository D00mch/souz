package ru.souz.llms

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import ru.souz.llms.codex.CodexOAuthCredentialStore
import ru.souz.llms.codex.CodexOAuthCredentials
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.db.SettingsProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodexOAuthServiceTest {
    @Test
    fun `concurrent services under a shared refresh lease call upstream once`() = runTest {
        val stale = credentials("access-0", version = 0, expiresAt = NOW)
        val store = CoordinatedMemoryCredentialStore(stale)
        var upstreamRefreshes = 0
        val services = listOf(
            CodexOAuthService(
                credentialStore = store,
                refreshCredentials = {
                    upstreamRefreshes += 1
                    credentials("access-a", version = 1, expiresAt = NOW + 3600)
                },
                nowEpochSeconds = { NOW },
            ),
            CodexOAuthService(
                credentialStore = store,
                refreshCredentials = {
                    upstreamRefreshes += 1
                    credentials("access-b", version = 1, expiresAt = NOW + 3600)
                },
                nowEpochSeconds = { NOW },
            ),
        )

        val results = services.map { service ->
            async { service.refreshTokenIfNeeded() }
        }.awaitAll()

        val winner = store.load()
        assertEquals(1, upstreamRefreshes)
        assertEquals(1, results.map { it.accessToken }.toSet().size)
        assertEquals(listOf(winner, winner), results)
        assertEquals(1L, results.first().version)
    }

    @Test
    fun `failed refresh does not return an unchanged expired credential`() = runTest {
        val stale = credentials("access-0", version = 0, expiresAt = NOW)
        val service = CodexOAuthService(
            credentialStore = MemoryCredentialStore(stale),
            refreshCredentials = { null },
            nowEpochSeconds = { NOW },
        )

        assertFailsWith<IllegalStateException> { service.refreshTokenIfNeeded() }
    }

    @Test
    fun `desktop settings preserve legacy access token only sessions`() = runTest {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true) {
            every { codexAccessToken } returns "desktop-access-token"
            every { codexRefreshToken } returns null
            every { codexAccountId } returns null
            every { codexExpiresAt } returns null
        }

        val credentials = CodexOAuthService(settingsProvider).refreshTokenIfNeeded()

        assertEquals("desktop-access-token", credentials.accessToken)
        assertEquals(null, credentials.refreshToken)
        assertEquals(null, credentials.accountId)
        assertEquals(null, credentials.expiresAtEpochSeconds)
    }

    private fun credentials(accessToken: String, version: Long, expiresAt: Long) =
        CodexOAuthCredentials(
            accessToken = accessToken,
            refreshToken = "refresh-$version",
            accountId = "account",
            expiresAtEpochSeconds = expiresAt,
            version = version,
        )

    private class MemoryCredentialStore(initial: CodexOAuthCredentials) : CodexOAuthCredentialStore {
        private val mutex = Mutex()
        private var current = initial

        override suspend fun load(): CodexOAuthCredentials = mutex.withLock { current }

        override suspend fun compareAndSet(
            expectedVersion: Long?,
            credentials: CodexOAuthCredentials,
        ): Boolean = mutex.withLock {
            if (current.version != expectedVersion) return@withLock false
            current = credentials
            true
        }
    }

    private class CoordinatedMemoryCredentialStore(
        initial: CodexOAuthCredentials,
    ) : CodexOAuthCredentialStore {
        private val stateMutex = Mutex()
        private val refreshLease = Mutex()
        private var current = initial

        override suspend fun load(): CodexOAuthCredentials = stateMutex.withLock { current }

        override suspend fun compareAndSet(
            expectedVersion: Long?,
            credentials: CodexOAuthCredentials,
        ): Boolean = stateMutex.withLock {
            if (current.version != expectedVersion) return@withLock false
            current = credentials
            true
        }

        override suspend fun <T> withRefreshLease(
            action: suspend (leasedStore: CodexOAuthCredentialStore) -> T,
        ): T = refreshLease.withLock { action(this) }
    }

    private companion object {
        const val NOW = 2_000_000_000L
    }
}
