package ru.souz.backend.app

import java.nio.file.Path
import ru.souz.backend.common.BackendConfigurationException
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.config.SystemBackendConfigSource
import ru.souz.backend.storage.StorageMode

data class BackendLlmLimits(
    val perUserConcurrentExecutions: Int = 4,
    val perUserRequestsPerMinute: Int = 30,
    val perUserTokensPerMinute: Int = 120_000,
    val globalProviderConcurrency: Int = 8,
) {
    fun validate(): BackendLlmLimits {
        if (perUserConcurrentExecutions <= 0) {
            throw BackendConfigurationException("Per-user concurrent executions limit must be positive.")
        }
        if (perUserRequestsPerMinute <= 0) {
            throw BackendConfigurationException("Per-user requests per minute limit must be positive.")
        }
        if (perUserTokensPerMinute <= 0) {
            throw BackendConfigurationException("Per-user tokens per minute limit must be positive.")
        }
        if (globalProviderConcurrency <= 0) {
            throw BackendConfigurationException("Global provider concurrency limit must be positive.")
        }
        return this
    }
}

data class BackendProviderRetryPolicy(
    val max429Retries: Int = 2,
    val backoffBaseMs: Long = 500L,
    val backoffMaxMs: Long = 5_000L,
) {
    fun validate(): BackendProviderRetryPolicy {
        if (max429Retries < 0) {
            throw BackendConfigurationException("Provider 429 retries must not be negative.")
        }
        if (backoffBaseMs <= 0L) {
            throw BackendConfigurationException("Provider backoff base must be positive.")
        }
        if (backoffMaxMs <= 0L) {
            throw BackendConfigurationException("Provider backoff max must be positive.")
        }
        if (backoffMaxMs < backoffBaseMs) {
            throw BackendConfigurationException("Provider backoff max must be greater than or equal to base.")
        }
        return this
    }
}

data class BackendPostgresConfig(
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String?,
    val schema: String,
    val maxPoolSize: Int,
    val connectionTimeoutMs: Long,
) {
    fun validate(): BackendPostgresConfig {
        requireText(host, "SOUZ_BACKEND_DB_HOST / souz.backend.db.host")
        if (port !in 1..65_535) {
            throw BackendConfigurationException("Postgres port must be between 1 and 65535.")
        }
        requireText(database, "SOUZ_BACKEND_DB_NAME / souz.backend.db.name")
        requireText(user, "SOUZ_BACKEND_DB_USER / souz.backend.db.user")
        requireText(schema, "SOUZ_BACKEND_DB_SCHEMA / souz.backend.db.schema")
        if (maxPoolSize <= 0) {
            throw BackendConfigurationException("Postgres max pool size must be positive.")
        }
        if (connectionTimeoutMs <= 0L) {
            throw BackendConfigurationException("Postgres connection timeout must be positive.")
        }
        return this
    }

    private fun requireText(value: String, keyDescription: String) {
        if (value.isBlank()) {
            throw BackendConfigurationException("$keyDescription must not be blank.")
        }
    }
}

data class BackendAppConfig(
    val featureFlags: BackendFeatureFlags,
    val storageMode: StorageMode,
    val proxyToken: String?,
    val dataDir: Path,
    val masterKey: String? = null,
    val telegramTokenEncryptionKey: String? = null,
    val telegramPollingMaxConcurrency: Int = 4,
    val llmLimits: BackendLlmLimits = BackendLlmLimits(),
    val providerRetryPolicy: BackendProviderRetryPolicy = BackendProviderRetryPolicy(),
    val postgres: BackendPostgresConfig? = null,
) {
    fun validate(): BackendAppConfig {
        storageMode.requireSupported()
        if (masterKey.isNullOrBlank()) {
            throw BackendConfigurationException("SOUZ_MASTER_KEY / souz.masterKey must not be blank.")
        }
        if (telegramTokenEncryptionKey.isNullOrBlank()) {
            throw BackendConfigurationException(
                "TELEGRAM_TOKEN_ENCRYPTION_KEY / souz.telegram.tokenEncryptionKey must not be blank."
            )
        }
        if (telegramPollingMaxConcurrency <= 0) {
            throw BackendConfigurationException("Telegram polling max concurrency must be positive.")
        }
        llmLimits.validate()
        providerRetryPolicy.validate()
        when (storageMode) {
            StorageMode.POSTGRES -> {
                postgres?.validate()
                    ?: throw BackendConfigurationException(
                        "Postgres storage mode requires explicit database configuration."
                    )
            }

            StorageMode.MEMORY,
            StorageMode.FILESYSTEM,
            -> Unit
        }
        return this
    }

    companion object {
        fun load(source: BackendConfigSource = SystemBackendConfigSource): BackendAppConfig =
            BackendAppConfig(
                featureFlags = BackendFeatureFlags.load(source),
                storageMode = StorageMode.load(source),
                proxyToken = source.value(
                    envKey = "SOUZ_BACKEND_PROXY_TOKEN",
                    propertyKey = "souz.backend.proxyToken",
                )?.trim()?.takeIf { it.isNotEmpty() },
                masterKey = source.value(
                    envKey = "SOUZ_MASTER_KEY",
                    propertyKey = "souz.masterKey",
                )?.trim()?.takeIf { it.isNotEmpty() },
                telegramTokenEncryptionKey = source.value(
                    envKey = "TELEGRAM_TOKEN_ENCRYPTION_KEY",
                    propertyKey = "souz.telegram.tokenEncryptionKey",
                )?.trim()?.takeIf { it.isNotEmpty() },
                telegramPollingMaxConcurrency = source.intValue(
                    envKey = "SOUZ_TELEGRAM_POLLING_MAX_CONCURRENCY",
                    propertyKey = "souz.telegram.pollingMaxConcurrency",
                    default = 4,
                ),
                dataDir = Path.of(
                    source.value(
                        envKey = "SOUZ_BACKEND_DATA_DIR",
                        propertyKey = "souz.backend.dataDir",
                    )?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "data"
                ).normalize(),
                llmLimits = BackendLlmLimits(
                    perUserConcurrentExecutions = source.intValue(
                        envKey = "SOUZ_BACKEND_LIMIT_PER_USER_CONCURRENT_EXECUTIONS",
                        propertyKey = "souz.backend.limit.perUserConcurrentExecutions",
                        default = 4,
                    ),
                    perUserRequestsPerMinute = source.intValue(
                        envKey = "SOUZ_BACKEND_LIMIT_PER_USER_REQUESTS_PER_MINUTE",
                        propertyKey = "souz.backend.limit.perUserRequestsPerMinute",
                        default = 30,
                    ),
                    perUserTokensPerMinute = source.intValue(
                        envKey = "SOUZ_BACKEND_LIMIT_PER_USER_TOKENS_PER_MINUTE",
                        propertyKey = "souz.backend.limit.perUserTokensPerMinute",
                        default = 120_000,
                    ),
                    globalProviderConcurrency = source.intValue(
                        envKey = "SOUZ_BACKEND_LIMIT_GLOBAL_PROVIDER_CONCURRENCY",
                        propertyKey = "souz.backend.limit.globalProviderConcurrency",
                        default = 8,
                    ),
                ),
                providerRetryPolicy = BackendProviderRetryPolicy(
                    max429Retries = source.intValue(
                        envKey = "SOUZ_BACKEND_PROVIDER_MAX_429_RETRIES",
                        propertyKey = "souz.backend.provider.max429Retries",
                        default = 2,
                    ),
                    backoffBaseMs = source.longValue(
                        envKey = "SOUZ_BACKEND_PROVIDER_BACKOFF_BASE_MS",
                        propertyKey = "souz.backend.provider.backoffBaseMs",
                        default = 500L,
                    ),
                    backoffMaxMs = source.longValue(
                        envKey = "SOUZ_BACKEND_PROVIDER_BACKOFF_MAX_MS",
                        propertyKey = "souz.backend.provider.backoffMaxMs",
                        default = 5_000L,
                    ),
                ),
                postgres = StorageMode.load(source)
                    .takeIf { it == StorageMode.POSTGRES }
                    ?.let {
                        BackendPostgresConfig(
                            host = source.stringValue(
                                envKey = "SOUZ_BACKEND_DB_HOST",
                                propertyKey = "souz.backend.db.host",
                                default = "127.0.0.1",
                            ),
                            port = source.intValue(
                                envKey = "SOUZ_BACKEND_DB_PORT",
                                propertyKey = "souz.backend.db.port",
                                default = 5432,
                            ),
                            database = source.stringValue(
                                envKey = "SOUZ_BACKEND_DB_NAME",
                                propertyKey = "souz.backend.db.name",
                                default = "souz",
                            ),
                            user = source.stringValue(
                                envKey = "SOUZ_BACKEND_DB_USER",
                                propertyKey = "souz.backend.db.user",
                                default = "souz",
                            ),
                            password = source.value(
                                envKey = "SOUZ_BACKEND_DB_PASSWORD",
                                propertyKey = "souz.backend.db.password",
                            )?.trim()?.takeIf { it.isNotEmpty() },
                            schema = source.stringValue(
                                envKey = "SOUZ_BACKEND_DB_SCHEMA",
                                propertyKey = "souz.backend.db.schema",
                                default = "public",
                            ),
                            maxPoolSize = source.intValue(
                                envKey = "SOUZ_BACKEND_DB_MAX_POOL_SIZE",
                                propertyKey = "souz.backend.db.maxPoolSize",
                                default = 10,
                            ),
                            connectionTimeoutMs = source.longValue(
                                envKey = "SOUZ_BACKEND_DB_CONNECTION_TIMEOUT_MS",
                                propertyKey = "souz.backend.db.connectionTimeoutMs",
                                default = 30_000L,
                            ),
                        )
                    },
            )
    }
}

private fun BackendConfigSource.stringValue(
    envKey: String,
    propertyKey: String,
    default: String,
): String =
    value(envKey, propertyKey)?.trim()?.takeIf { it.isNotEmpty() } ?: default

private fun BackendConfigSource.intValue(
    envKey: String,
    propertyKey: String,
    default: Int,
): Int {
    val rawValue = value(envKey, propertyKey)?.trim()?.takeIf { it.isNotEmpty() } ?: return default
    return rawValue.toIntOrNull()
        ?: throw BackendConfigurationException("Invalid integer value '$rawValue' for $envKey / $propertyKey.")
}

private fun BackendConfigSource.longValue(
    envKey: String,
    propertyKey: String,
    default: Long,
): Long {
    val rawValue = value(envKey, propertyKey)?.trim()?.takeIf { it.isNotEmpty() } ?: return default
    return rawValue.toLongOrNull()
        ?: throw BackendConfigurationException("Invalid long value '$rawValue' for $envKey / $propertyKey.")
}
