package ru.souz.jobs.impl

import javax.sql.DataSource
import org.flywaydb.core.Flyway

object JobMigrations {
    fun migrate(dataSource: DataSource, schema: String) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration-jobs")
            .table("flyway_schema_history_jobs")
            .defaultSchema(schema)
            .schemas(schema)
            .createSchemas(false)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()
    }
}
