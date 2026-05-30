package ru.souz.backend.storage.postgres

import java.nio.file.Files
import java.util.UUID
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import ru.souz.backend.app.BackendAppConfig
import ru.souz.backend.app.BackendPostgresConfig
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.storage.StorageMode

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

internal fun newPostgresSchema(prefix: String): String =
    "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"

internal fun postgresAppConfig(
    schema: String,
    durableEventReplay: Boolean = true,
): BackendAppConfig {
    assumeTrue(
        runCatching { DockerClientFactory.instance().isDockerAvailable() }.getOrDefault(false),
        "Docker is required for Postgres Testcontainers tests.",
    )
    val container = SharedPostgresContainer.instance
    return BackendAppConfig(
        featureFlags = BackendFeatureFlags(
            durableEventReplay = durableEventReplay,
        ),
        storageMode = StorageMode.POSTGRES,
        proxyToken = null,
        dataDir = Files.createTempDirectory("backend-postgres-config"),
        masterKey = "test-master-key",
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
