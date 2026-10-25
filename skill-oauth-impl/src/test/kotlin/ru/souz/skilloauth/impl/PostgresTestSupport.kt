package ru.souz.skilloauth.impl

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Mirrors `:backend`'s `PostgresTestSupport` but is self-contained, since `:skill-oauth-impl` must
 *  not depend on `:backend`. */
internal object SharedSkillOAuthPostgresContainer {
    val instance: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            withDatabaseName("souz")
            withUsername("souz")
            withPassword("souz")
            start()
        }
    }
}

// Postgres silently truncates identifiers longer than NAMEDATALEN-1 (63 bytes). A full,
// undelimited UUID suffix (32 hex chars) pushed some of this file's longer test-name prefixes
// (e.g. "skill_oauth_credential_multi_provider") past that limit: the schema got created under
// its truncated name, but Hikari/Flyway kept using the full, untruncated string as the "current
// schema" -- a name that never actually existed -- and failed deep inside migration with a
// PSQLException. 12 hex chars (48 bits) is still effectively collision-free for one test run's
// worth of schemas, and capping the prefix defends the same way against any future longer name.
private const val MAX_SCHEMA_NAME_LENGTH = 63
private const val SCHEMA_SUFFIX_LENGTH = 12

internal fun newSkillOAuthTestSchema(prefix: String): String {
    val suffix = UUID.randomUUID().toString().replace("-", "").take(SCHEMA_SUFFIX_LENGTH)
    val safePrefix = prefix.take(MAX_SCHEMA_NAME_LENGTH - 1 - SCHEMA_SUFFIX_LENGTH)
    return "${safePrefix}_$suffix"
}

internal fun skillOAuthTestDataSource(schema: String): HikariDataSource {
    assumeTrue(
        runCatching { DockerClientFactory.instance().isDockerAvailable() }.getOrDefault(false),
        "Docker is required for Postgres Testcontainers tests.",
    )
    val container = SharedSkillOAuthPostgresContainer.instance
    val jdbcUrl = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}"

    Properties().apply {
        setProperty("user", container.username)
        setProperty("password", container.password)
    }.let { properties ->
        DriverManager.getConnection(jdbcUrl, properties).use { connection ->
            connection.createStatement().use { it.execute("create schema if not exists \"$schema\"") }
        }
    }

    val hikariConfig = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        username = container.username
        password = container.password
        maximumPoolSize = 4
        this.schema = schema
        addDataSourceProperty("currentSchema", schema)
    }
    return HikariDataSource(hikariConfig).also { dataSource ->
        SkillOAuthMigrations.migrate(dataSource, schema)
    }
}
