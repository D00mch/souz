package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.DriverManager
import java.util.Properties
import org.flywaydb.core.Flyway
import ru.souz.backend.app.BackendPostgresConfig

object PostgresDataSourceFactory {
    fun create(config: BackendPostgresConfig): HikariDataSource {
        val postgresConfig = config.validate()
        val schema = postgresConfig.schema.postgresIdentifier()
        val jdbcUrl = postgresConfig.jdbcUrl()
        ensureSchemaExists(jdbcUrl, postgresConfig, schema)

        val hikariConfig = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = postgresConfig.user
            password = postgresConfig.password
            maximumPoolSize = postgresConfig.maxPoolSize
            connectionTimeout = postgresConfig.connectionTimeoutMs
            poolName = "souz-backend-postgres"
            this.schema = schema
            addDataSourceProperty("currentSchema", schema)
            addDataSourceProperty("ApplicationName", "souz-backend")
        }
        val dataSource = HikariDataSource(hikariConfig)
        return initializeDataSource(dataSource) {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(false)
                .load()
                .migrate()
        }
    }

    private fun ensureSchemaExists(
        jdbcUrl: String,
        config: BackendPostgresConfig,
        schema: String,
    ) {
        val properties = Properties().apply {
            setProperty("user", config.user)
            config.password?.let { setProperty("password", it) }
        }
        DriverManager.getConnection(jdbcUrl, properties).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("create schema if not exists ${schema.sqlIdentifier()}")
            }
        }
    }

    private fun String.postgresIdentifier(): String =
        take(MAX_POSTGRES_IDENTIFIER_LENGTH)

    private fun String.sqlIdentifier(): String = "\"${replace("\"", "\"\"")}\""

    private const val MAX_POSTGRES_IDENTIFIER_LENGTH = 63
}

internal fun initializeDataSource(
    dataSource: HikariDataSource,
    initialize: (HikariDataSource) -> Unit,
): HikariDataSource = try {
    initialize(dataSource)
    dataSource
} catch (failure: Throwable) {
    try {
        dataSource.close()
    } catch (closeFailure: Throwable) {
        failure.addSuppressed(closeFailure)
    }
    throw failure
}
