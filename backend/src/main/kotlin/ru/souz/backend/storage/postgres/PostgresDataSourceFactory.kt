package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import ru.souz.backend.app.BackendPostgresConfig

object PostgresDataSourceFactory {
    fun create(config: BackendPostgresConfig): HikariDataSource {
        config.validate()
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://${config.host}:${config.port}/${config.database}"
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            connectionTimeout = config.connectionTimeoutMs
            poolName = "souz-backend-postgres"
            schema = config.schema
            addDataSourceProperty("currentSchema", config.schema)
            addDataSourceProperty("ApplicationName", "souz-backend")
        }
        return HikariDataSource(hikariConfig).also { dataSource ->
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema(config.schema)
                .schemas(config.schema)
                .createSchemas(true)
                .load()
                .migrate()
        }
    }
}
