package ru.souz.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.souz.backend.app.BackendAppConfig
import ru.souz.backend.app.BackendLlmLimits
import ru.souz.backend.app.BackendPostgresConfig
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.backend.app.BackendServerConfig
import ru.souz.backend.common.BackendConfigurationException

class BackendFeatureFlagsTest {
    @Test
    fun `feature flags default to false`() {
        val flags = BackendFeatureFlags.load(MapBackendConfigSource())

        assertFalse(flags.wsEvents)
        assertFalse(flags.streamingMessages)
        assertFalse(flags.toolEvents)
        assertFalse(flags.options)
        assertFalse(flags.telegramBot)
    }

    @Test
    fun `feature flags read env and property keys`() {
        val flags = BackendFeatureFlags.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_FEATURE_WS_EVENTS" to "true",
                    "SOUZ_FEATURE_STREAMING_MESSAGES" to "TRUE",
                    "ENABLE_BACKEND_TG_FEATURE" to "true",
                ),
                properties = mapOf(
                    "souz.backend.feature.toolEvents" to "true",
                    "souz.backend.feature.options" to "true",
                ),
            )
        )

        assertTrue(flags.wsEvents)
        assertTrue(flags.streamingMessages)
        assertTrue(flags.toolEvents)
        assertTrue(flags.options)
        assertTrue(flags.telegramBot)
    }
}

class BackendAppConfigTest {
    @Test
    fun `server config reads defaults and explicit settings`() {
        val defaultConfig = BackendAppConfig.load(
            MapBackendConfigSource(
                env = mapOf("SOUZ_MASTER_KEY" to "test-master-key"),
            )
        ).validate()
        val envConfig = BackendAppConfig.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_BACKEND_HOST" to " 0.0.0.0 ",
                    "SOUZ_BACKEND_PORT" to "9090",
                    "SOUZ_BACKEND_PROXY_TOKEN" to " proxy-secret ",
                    "SOUZ_MASTER_KEY" to "test-master-key",
                ),
            )
        ).validate()
        val propertyConfig = BackendAppConfig.load(
            MapBackendConfigSource(
                properties = mapOf(
                    "souz.backend.host" to "backend.internal",
                    "souz.backend.port" to "8181",
                    "souz.backend.proxyToken" to " property-secret ",
                    "souz.masterKey" to "test-master-key",
                ),
            )
        ).validate()
        val blankTokenConfig = BackendAppConfig.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_BACKEND_PROXY_TOKEN" to "   ",
                    "SOUZ_MASTER_KEY" to "test-master-key",
                ),
            )
        ).validate()

        assertEquals(
            BackendServerConfig(host = "127.0.0.1", port = 8080, proxyToken = null),
            defaultConfig.server,
        )
        assertEquals(
            BackendServerConfig(host = "0.0.0.0", port = 9090, proxyToken = "proxy-secret"),
            envConfig.server,
        )
        assertEquals(
            BackendServerConfig(host = "backend.internal", port = 8181, proxyToken = "property-secret"),
            propertyConfig.server,
        )
        assertNull(blankTokenConfig.server.proxyToken)
    }

    @Test
    fun `server config rejects malformed and out of range ports`() {
        val malformedPort = assertFailsWith<BackendConfigurationException> {
            BackendAppConfig.load(
                MapBackendConfigSource(
                    env = mapOf(
                        "SOUZ_BACKEND_PORT" to "not-a-port",
                        "SOUZ_MASTER_KEY" to "test-master-key",
                    ),
                )
            )
        }

        assertTrue(malformedPort.message.orEmpty().contains("SOUZ_BACKEND_PORT"))
        listOf(0, 65_536).forEach { invalidPort ->
            val error = assertFailsWith<BackendConfigurationException> {
                BackendServerConfig(
                    host = "127.0.0.1",
                    port = invalidPort,
                    proxyToken = null,
                ).validate()
            }
            assertTrue(error.message.orEmpty().contains("between 1 and 65535"))
        }
    }

    @Test
    fun `server config rejects blank host from config source`() {
        val sources = listOf(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_BACKEND_HOST" to "   ",
                    "SOUZ_MASTER_KEY" to "test-master-key",
                ),
            ),
            MapBackendConfigSource(
                properties = mapOf(
                    "souz.backend.host" to "   ",
                    "souz.masterKey" to "test-master-key",
                ),
            ),
        )

        sources.forEach { source ->
            val error = assertFailsWith<BackendConfigurationException> {
                BackendAppConfig.load(source).validate()
            }

            assertTrue(error.message.orEmpty().contains("SOUZ_BACKEND_HOST"))
        }
    }

    @Test
    fun `postgres config reads defaults and explicit db settings`() {
        val config = BackendAppConfig.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_MASTER_KEY" to "postgres-master-key",
                    "TELEGRAM_TOKEN_ENCRYPTION_KEY" to TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY,
                )
            )
        ).validate()
        val propertyConfig = BackendAppConfig.load(
            MapBackendConfigSource(
                properties = mapOf(
                    "souz.backend.db.host" to "db.internal",
                    "souz.backend.db.port" to "5544",
                    "souz.backend.db.name" to "souz_prod",
                    "souz.backend.db.user" to "souz_user",
                    "souz.backend.db.password" to "top-secret",
                    "souz.backend.db.schema" to "backend_stage10",
                    "souz.backend.db.maxPoolSize" to "17",
                    "souz.backend.db.connectionTimeoutMs" to "45000",
                    "souz.masterKey" to "postgres-prop-master-key",
                    "souz.telegram.tokenEncryptionKey" to TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY,
                ),
            )
        ).validate()

        assertEquals(
            BackendPostgresConfig(
                host = "127.0.0.1",
                port = 5432,
                database = "souz",
                user = "souz",
                password = null,
                schema = "public",
                maxPoolSize = 10,
                connectionTimeoutMs = 30_000L,
            ),
            config.postgres,
        )
        assertEquals(
            BackendPostgresConfig(
                host = "db.internal",
                port = 5544,
                database = "souz_prod",
                user = "souz_user",
                password = "top-secret",
                schema = "backend_stage10",
                maxPoolSize = 17,
                connectionTimeoutMs = 45_000L,
            ),
            propertyConfig.postgres,
        )
        assertEquals("postgres-master-key", config.masterKey)
        assertEquals("postgres-prop-master-key", propertyConfig.masterKey)
    }

    @Test
    fun `backend config reads llm limits and retry policy`() {
        val config = BackendAppConfig.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_MASTER_KEY" to "test-master-key",
                    "TELEGRAM_TOKEN_ENCRYPTION_KEY" to TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY,
                    "SOUZ_BACKEND_LIMIT_PER_USER_CONCURRENT_EXECUTIONS" to "3",
                    "SOUZ_BACKEND_LIMIT_PER_USER_REQUESTS_PER_MINUTE" to "17",
                    "SOUZ_BACKEND_LIMIT_PER_USER_TOKENS_PER_MINUTE" to "32000",
                    "SOUZ_BACKEND_LIMIT_GLOBAL_PROVIDER_CONCURRENCY" to "5",
                ),
                properties = mapOf(
                    "souz.backend.provider.max429Retries" to "4",
                    "souz.backend.provider.backoffBaseMs" to "600",
                    "souz.backend.provider.backoffMaxMs" to "4000",
                ),
            )
        ).validate()

        assertEquals(
            BackendLlmLimits(
                perUserConcurrentExecutions = 3,
                perUserRequestsPerMinute = 17,
                perUserTokensPerMinute = 32_000,
                globalProviderConcurrency = 5,
            ),
            config.llmLimits,
        )
        assertEquals(
            BackendProviderRetryPolicy(
                max429Retries = 4,
                backoffBaseMs = 600L,
                backoffMaxMs = 4_000L,
            ),
            config.providerRetryPolicy,
        )
    }

    @Test
    fun `backend config requires explicit master key for encrypted user provider keys`() {
        val error = assertFailsWith<BackendConfigurationException> {
            BackendAppConfig.load(
                MapBackendConfigSource()
            ).validate()
        }

        assertTrue(error.message.orEmpty().contains("SOUZ_MASTER_KEY"))
    }

    @Test
    fun `backend config does not require telegram encryption key when telegram feature is disabled`() {
        val config = BackendAppConfig.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_MASTER_KEY" to "test-master-key",
                )
            )
        ).validate()

        assertFalse(config.featureFlags.telegramBot)
        assertNull(config.telegramTokenEncryptionKey)
    }

    @Test
    fun `backend config requires telegram encryption key when telegram feature is enabled`() {
        val error = assertFailsWith<BackendConfigurationException> {
            BackendAppConfig.load(
                MapBackendConfigSource(
                    env = mapOf(
                        "SOUZ_MASTER_KEY" to "test-master-key",
                        "ENABLE_BACKEND_TG_FEATURE" to "true",
                    )
                )
            ).validate()
        }

        assertTrue(error.message.orEmpty().contains("TELEGRAM_TOKEN_ENCRYPTION_KEY"))
    }

    @Test
    fun `backend config rejects invalid llm limits and retry policy`() {
        val invalidLimit = assertFailsWith<BackendConfigurationException> {
            BackendAppConfig.load(
                MapBackendConfigSource(
                    env = mapOf(
                        "SOUZ_MASTER_KEY" to "test-master-key",
                        "TELEGRAM_TOKEN_ENCRYPTION_KEY" to TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY,
                        "SOUZ_BACKEND_LIMIT_PER_USER_REQUESTS_PER_MINUTE" to "0",
                    )
                )
            ).validate()
        }
        val invalidRetry = assertFailsWith<BackendConfigurationException> {
            BackendAppConfig.load(
                MapBackendConfigSource(
                    env = mapOf(
                        "SOUZ_MASTER_KEY" to "test-master-key",
                        "TELEGRAM_TOKEN_ENCRYPTION_KEY" to TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY,
                    ),
                    properties = mapOf(
                        "souz.backend.provider.max429Retries" to "-1",
                    ),
                )
            ).validate()
        }

        assertTrue(invalidLimit.message.orEmpty().contains("requests"))
        assertTrue(invalidRetry.message.orEmpty().contains("429"))
    }
}

private class MapBackendConfigSource(
    private val env: Map<String, String> = emptyMap(),
    private val properties: Map<String, String> = emptyMap(),
) : BackendConfigSource {
    override fun env(key: String): String? = env[key]

    override fun property(key: String): String? = properties[key]
}

private const val TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
