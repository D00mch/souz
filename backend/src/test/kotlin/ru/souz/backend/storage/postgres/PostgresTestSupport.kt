package ru.souz.backend.storage.postgres

import java.util.UUID
import java.nio.file.Files
import java.nio.file.Path
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import ru.souz.backend.app.BackendAppConfig
import ru.souz.backend.app.BackendPostgresConfig
import ru.souz.backend.app.BackendServerConfig
import ru.souz.backend.app.SkillOAuthProviderCredentials
import ru.souz.backend.config.BackendFeatureFlags

internal object SharedPostgresContainer {
    val instance: PostgreSQLContainer<Nothing> by lazy {
        requireDocker()
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
    featureFlags: BackendFeatureFlags = BackendFeatureFlags(),
    proxyToken: String? = null,
    telegramTokenEncryptionKey: String? = null,
    includeSkillOAuthConfig: Boolean = true,
): BackendAppConfig {
    val container = SharedPostgresContainer.instance
    return BackendAppConfig(
        featureFlags = featureFlags,
        server = BackendServerConfig(
            host = "127.0.0.1",
            port = 8080,
            proxyToken = proxyToken,
        ),
        masterKey = "test-master-key",
        telegramTokenEncryptionKey = telegramTokenEncryptionKey,
        skillOAuthTokenEncryptionKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
            .takeIf { includeSkillOAuthConfig },
        skillOAuthProviderCredentials = if (includeSkillOAuthConfig) mapOf(
            "yandex" to SkillOAuthProviderCredentials(
                clientId = "test-yandex-client-id",
                clientSecret = "test-yandex-client-secret",
                redirectUri = "https://backend.test/oauth/callback",
            )
        ) else emptyMap(),
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

private fun requireDocker() {
    configureDockerHostForDesktop()
    if (runCatching { DockerClientFactory.instance().isDockerAvailable() }.getOrDefault(false)) {
        return
    }
    error("Docker is required for backend Postgres Testcontainers tests. Start Docker and rerun :backend:test.")
}

private fun configureDockerHostForDesktop() {
    if (!System.getenv("DOCKER_HOST").isNullOrBlank() || !System.getProperty("docker.host").isNullOrBlank()) {
        return
    }
    val desktopSocket = Path.of(System.getProperty("user.home"), ".docker", "run", "docker.sock")
    if (Files.exists(desktopSocket)) {
        System.setProperty("docker.host", "unix://$desktopSocket")
    }
}
