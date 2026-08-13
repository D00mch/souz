package ru.souz.backend.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import ru.souz.backend.common.BackendConfigurationException
import ru.souz.backend.config.BackendConfigSource

class BackendCodexOAuthConfigTest {
    @Test
    fun `Codex seed is absent when no deployment credentials are configured`() {
        assertNull(BackendAppConfig.load(configSource()).codexOAuthSeed)
    }

    @Test
    fun `Codex seed loads only as one complete immutable credential set`() {
        val config = BackendAppConfig.load(
            configSource(
                "CODEX_ACCESS_TOKEN" to "access",
                "CODEX_REFRESH_TOKEN" to "refresh",
                "CODEX_ACCOUNT_ID" to "account",
                "CODEX_EXPIRES_AT" to "1900000000",
            )
        )

        assertEquals(
            BackendCodexOAuthSeed("access", "refresh", "account", 1_900_000_000L),
            config.codexOAuthSeed,
        )
    }

    @Test
    fun `partial Codex deployment credentials fail configuration`() {
        assertFailsWith<BackendConfigurationException> {
            BackendAppConfig.load(configSource("CODEX_ACCESS_TOKEN" to "access"))
        }
    }

    private fun configSource(vararg values: Pair<String, String>): BackendConfigSource {
        val environment = values.toMap() + mapOf("SOUZ_MASTER_KEY" to "test-master-key")
        return object : BackendConfigSource {
            override fun env(key: String): String? = environment[key]
            override fun property(key: String): String? = null
        }
    }
}
