package ru.souz.jobs.impl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobSchedule

private object JobTestPostgres {
    val container: PostgreSQLContainer<Nothing> by lazy {
        if (System.getenv("DOCKER_HOST").isNullOrBlank() && System.getProperty("docker.host").isNullOrBlank()) {
            val socket = Path.of(System.getProperty("user.home"), ".docker", "run", "docker.sock")
            if (Files.exists(socket)) System.setProperty("docker.host", "unix://$socket")
        }
        check(DockerClientFactory.instance().isDockerAvailable) { "Docker is required for :job-impl:test." }
        PostgreSQLContainer<Nothing>("postgres:16-alpine").apply { start() }
    }
}

private fun jobTestDataSource(): HikariDataSource {
    val postgres = JobTestPostgres.container
    val schema = "jobs_${UUID.randomUUID().toString().replace("-", "")}"
    DriverManager.getConnection(postgres.jdbcUrl, Properties().apply {
        setProperty("user", postgres.username)
        setProperty("password", postgres.password)
    }).use { connection ->
        connection.createStatement().use { it.execute("create schema $schema") }
    }
    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = postgres.jdbcUrl
        username = postgres.username
        password = postgres.password
        maximumPoolSize = 4
        this.schema = schema
        addDataSourceProperty("currentSchema", schema)
    })
    JobMigrations.migrate(dataSource, schema)
    return dataSource
}

internal val jobTestMapper = jacksonObjectMapper()

internal fun jobTest(
    timing: JobWorkerTiming = JobWorkerTiming(),
    test: suspend CoroutineScope.(HikariDataSource, PostgresJobService) -> Unit,
) = runBlocking {
    jobTestDataSource().use { db ->
        coroutineScope {
            try {
                test(db, PostgresJobService(db, timing))
            } finally {
                coroutineContext.cancelChildren()
            }
        }
    }
}

internal suspend fun PostgresJobService.create(
    title: String = "Test job",
    userId: String = "owner",
    schedule: JobSchedule = JobSchedule.Once(),
) = createJob(userId, CreateJobRequest(title, jobTestMapper.readTree("{\"task\":\"example\"}"), schedule))

internal fun DataSource.execute(sql: String, vararg values: Any?) = connection.use { connection ->
    connection.prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }
}

internal fun DataSource.databaseNow(): Instant = connection.use { connection ->
    connection.createStatement().use { statement ->
        statement.executeQuery("select clock_timestamp()").use { rows ->
            rows.next()
            rows.getObject(1, OffsetDateTime::class.java).toInstant()
        }
    }
}
