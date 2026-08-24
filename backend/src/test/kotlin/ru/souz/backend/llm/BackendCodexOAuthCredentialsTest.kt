package ru.souz.backend.llm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendCodexOAuthCredentialsTest {
    @Test
    fun `complete Codex OAuth credentials require every field`() {
        assertTrue(completeCredentials().hasCompleteCodexOAuthCredentials())

        assertFalse(completeCredentials().apply { codexAccessToken = null }.hasCompleteCodexOAuthCredentials())
        assertFalse(completeCredentials().apply { codexRefreshToken = null }.hasCompleteCodexOAuthCredentials())
        assertFalse(completeCredentials().apply { codexAccountId = null }.hasCompleteCodexOAuthCredentials())
        assertFalse(completeCredentials().apply { codexExpiresAt = null }.hasCompleteCodexOAuthCredentials())
    }

    private fun completeCredentials(): LlmSettingsStub = LlmSettingsStub().apply {
        codexAccessToken = "server-codex-access-token"
        codexRefreshToken = "server-codex-refresh-token"
        codexAccountId = "server-codex-account-id"
        codexExpiresAt = 1_800_000_000L
    }
}
