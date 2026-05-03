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
        val jdbcUrl = "jdbc:postgresql://${postgresConfig.host}:${postgresConfig.port}/${postgresConfig.database}"
        ensureSchemaExists(jdbcUrl, postgresConfig)

        val hikariConfig = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = postgresConfig.user
            password = postgresConfig.password
            maximumPoolSize = postgresConfig.maxPoolSize
            connectionTimeout = postgresConfig.connectionTimeoutMs
            poolName = "souz-backend-postgres"
            schema = postgresConfig.schema
            addDataSourceProperty("currentSchema", postgresConfig.schema)
            addDataSourceProperty("ApplicationName", "souz-backend")
        }
        return HikariDataSource(hikariConfig).also { dataSource ->
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema(postgresConfig.schema)
                .schemas(postgresConfig.schema)
                .createSchemas(false)
                .load()
                .migrate()
        }
    }

    private fun ensureSchemaExists(jdbcUrl: String, config: BackendPostgresConfig) {
        val properties = Properties().apply {
            setProperty("user", config.user)
            config.password?.let { setProperty("password", it) }
        }
        DriverManager.getConnection(jdbcUrl, properties).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("create schema if not exists ${config.schema.sqlIdentifier()}")
            }
        }
    }

    private fun String.sqlIdentifier(): String = "\"${replace("\"", "\"\"")}\""
}
