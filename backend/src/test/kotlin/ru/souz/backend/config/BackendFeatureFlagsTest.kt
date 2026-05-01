package ru.souz.backend.config

import java.nio.file.Path
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
import ru.souz.backend.common.BackendConfigurationException
import ru.souz.backend.storage.StorageMode

class BackendFeatureFlagsTest {
    @Test
    fun `feature flags default to false`() {
        val flags = BackendFeatureFlags.load(MapBackendConfigSource())

        assertFalse(flags.wsEvents)
        assertFalse(flags.streamingMessages)
        assertFalse(flags.toolEvents)
        assertFalse(flags.choices)
        assertFalse(flags.durableEventReplay)
    }

    @Test
    fun `feature flags read env and property keys`() {
        val flags = BackendFeatureFlags.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_FEATURE_WS_EVENTS" to "true",
                    "SOUZ_FEATURE_STREAMING_MESSAGES" to "TRUE",
                ),
                properties = mapOf(
                    "souz.backend.feature.toolEvents" to "true",
                    "souz.backend.feature.choices" to "true",
                    "souz.backend.feature.durableEventReplay" to "true",
                ),
            )
        )

        assertTrue(flags.wsEvents)
        assertTrue(flags.streamingMessages)
        assertTrue(flags.toolEvents)
        assertTrue(flags.choices)
        assertTrue(flags.durableEventReplay)
    }
}

class StorageModeTest {
    @Test
    fun `storage mode defaults to memory`() {
        assertEquals(StorageMode.MEMORY, StorageMode.load(MapBackendConfigSource()))
    }

    @Test
    fun `storage mode reads config and accepts postgres`() {
        val filesystem = StorageMode.load(
            MapBackendConfigSource(env = mapOf("SOUZ_STORAGE_MODE" to "filesystem"))
        )
        val postgres = StorageMode.load(
            MapBackendConfigSource(properties = mapOf("souz.backend.storageMode" to "postgres"))
        )

        assertEquals(StorageMode.FILESYSTEM, filesystem)
        assertEquals(StorageMode.POSTGRES, postgres)
        assertEquals(StorageMode.FILESYSTEM, filesystem.requireSupported())
        assertEquals(StorageMode.POSTGRES, postgres.requireSupported())
    }

    @Test
    fun `storage mode rejects unknown values`() {
        val error = assertFailsWith<BackendConfigurationException> {
            StorageMode.load(
                MapBackendConfigSource(env = mapOf("SOUZ_STORAGE_MODE" to "mariadb"))
            )
        }

        assertTrue(error.message.orEmpty().contains("mariadb"))
    }
}

class BackendAppConfigTest {
    @Test
    fun `filesystem config defaults data dir and validates`() {
        val config = BackendAppConfig.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_STORAGE_MODE" to "filesystem",
                    "SOUZ_MASTER_KEY" to "test-master-key",
                )
            )
        ).validate()

        assertEquals(StorageMode.FILESYSTEM, config.storageMode)
        assertEquals(Path.of("data"), config.dataDir)
        assertEquals("test-master-key", config.masterKey)
    }

    @Test
    fun `filesystem config reads data dir from env and property`() {
        val envConfig = BackendAppConfig.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_STORAGE_MODE" to "filesystem",
                    "SOUZ_BACKEND_DATA_DIR" to "/tmp/souz-env-data",
                    "SOUZ_MASTER_KEY" to "env-master-key",
                )
            )
        )
        val propertyConfig = BackendAppConfig.load(
            MapBackendConfigSource(
                properties = mapOf(
                    "souz.backend.storageMode" to "filesystem",
                    "souz.backend.dataDir" to "/tmp/souz-prop-data",
                    "souz.masterKey" to "prop-master-key",
                )
            )
        )

        assertEquals(Path.of("/tmp/souz-env-data"), envConfig.dataDir)
        assertEquals(Path.of("/tmp/souz-prop-data"), propertyConfig.dataDir)
        assertEquals("env-master-key", envConfig.masterKey)
        assertEquals("prop-master-key", propertyConfig.masterKey)
    }

    @Test
    fun `postgres config reads defaults and explicit db settings`() {
        val defaultConfig = BackendAppConfig.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_STORAGE_MODE" to "postgres",
                    "SOUZ_MASTER_KEY" to "postgres-master-key",
                )
            )
        ).validate()
        val propertyConfig = BackendAppConfig.load(
            MapBackendConfigSource(
                properties = mapOf(
                    "souz.backend.storageMode" to "postgres",
                    "souz.backend.db.host" to "db.internal",
                    "souz.backend.db.port" to "5544",
                    "souz.backend.db.name" to "souz_prod",
                    "souz.backend.db.user" to "souz_user",
                    "souz.backend.db.password" to "top-secret",
                    "souz.backend.db.schema" to "backend_stage10",
                    "souz.backend.db.maxPoolSize" to "17",
                    "souz.backend.db.connectionTimeoutMs" to "45000",
                    "souz.masterKey" to "postgres-prop-master-key",
                ),
            )
        ).validate()

        assertEquals(StorageMode.POSTGRES, defaultConfig.storageMode)
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
            defaultConfig.postgres,
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
        assertEquals("postgres-master-key", defaultConfig.masterKey)
        assertEquals("postgres-prop-master-key", propertyConfig.masterKey)
    }

    @Test
    fun `filesystem config leaves postgres settings unset`() {
        val config = BackendAppConfig.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_STORAGE_MODE" to "filesystem",
                    "SOUZ_MASTER_KEY" to "test-master-key",
                )
            )
        ).validate()

        assertNull(config.postgres)
    }

    @Test
    fun `backend config reads llm limits and retry policy`() {
        val config = BackendAppConfig.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_STORAGE_MODE" to "memory",
                    "SOUZ_MASTER_KEY" to "test-master-key",
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
                MapBackendConfigSource(
                    env = mapOf("SOUZ_STORAGE_MODE" to "memory")
                )
            ).validate()
        }

        assertTrue(error.message.orEmpty().contains("SOUZ_MASTER_KEY"))
    }

    @Test
    fun `backend config rejects invalid llm limits and retry policy`() {
        val invalidLimit = assertFailsWith<BackendConfigurationException> {
            BackendAppConfig.load(
                MapBackendConfigSource(
                    env = mapOf(
                        "SOUZ_STORAGE_MODE" to "memory",
                        "SOUZ_MASTER_KEY" to "test-master-key",
                        "SOUZ_BACKEND_LIMIT_PER_USER_REQUESTS_PER_MINUTE" to "0",
                    )
                )
            ).validate()
        }
        val invalidRetry = assertFailsWith<BackendConfigurationException> {
            BackendAppConfig.load(
                MapBackendConfigSource(
                    env = mapOf(
                        "SOUZ_STORAGE_MODE" to "memory",
                        "SOUZ_MASTER_KEY" to "test-master-key",
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
