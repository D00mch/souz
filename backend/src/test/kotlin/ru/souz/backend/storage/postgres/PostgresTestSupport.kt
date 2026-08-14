package ru.souz.backend.storage.postgres

import java.util.UUID
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import ru.souz.backend.app.BackendAppConfig
import ru.souz.backend.app.BackendPostgresConfig
import ru.souz.backend.app.BackendServerConfig
import ru.souz.backend.app.SkillOAuthProviderCredentials
import ru.souz.backend.config.BackendFeatureFlags

internal object SharedPostgresContainer {
    val instance: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            withDatabaseName("souz")
            withUsername("souz")
            withPassword("souz")
            start()
        }
    }
}

// Postgres silently truncates identifiers longer than NAMEDATALEN-1 (63 bytes); a full 32-hex-char
// UUID suffix can push a long test-name prefix past that, creating a schema under a truncated name
// while Hikari/Flyway keep using the untruncated (never-actually-created) name and fail deep inside
// migration. 12 hex chars (48 bits) is still effectively collision-free for one test run's worth of
// schemas, and capping the prefix defends the same way against any future longer name.
private const val MAX_SCHEMA_NAME_LENGTH = 63
private const val SCHEMA_SUFFIX_LENGTH = 12

internal fun newPostgresSchema(prefix: String): String {
    val suffix = UUID.randomUUID().toString().replace("-", "").take(SCHEMA_SUFFIX_LENGTH)
    val safePrefix = prefix.take(MAX_SCHEMA_NAME_LENGTH - 1 - SCHEMA_SUFFIX_LENGTH)
    return "${safePrefix}_$suffix"
}

internal fun postgresAppConfig(
    schema: String,
): BackendAppConfig {
    assumeTrue(
        runCatching { DockerClientFactory.instance().isDockerAvailable() }.getOrDefault(false),
        "Docker is required for Postgres Testcontainers tests.",
    )
    val container = SharedPostgresContainer.instance
    return BackendAppConfig(
        featureFlags = BackendFeatureFlags(),
        server = BackendServerConfig(
            host = "127.0.0.1",
            port = 8080,
            proxyToken = null,
        ),
        masterKey = "test-master-key",
        skillOAuthTokenEncryptionKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        skillOAuthProviderCredentials = mapOf(
            "yandex" to SkillOAuthProviderCredentials(
                clientId = "test-yandex-client-id",
                clientSecret = "test-yandex-client-secret",
                redirectUri = "https://backend.test/oauth/callback",
            )
        ),
        postgres = BackendPostgresConfig(
            host = container.host,
            port = container.firstMappedPort,
            database = container.databaseName,
            user = container.username,
            password = container.password,
            schema = schema,
            maxPoolSize = 4,
            connectionTimeoutMs = 30_000L,
        ),
    ).validate()
}
