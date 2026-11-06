package ru.souz.backend.plugins

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.backend.storage.postgres.read
import ru.souz.backend.storage.postgres.write

internal fun databaseTest(block: suspend PluginDatabaseFixture.() -> Unit): Unit = runBlocking {
    PluginDatabaseFixture().use { it.block() }
}

/** Real PostgreSQL ownership checks, independent from runtime/classloader and S3 setup. */
internal class PluginDatabaseFixture : AutoCloseable {
    val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(newPostgresSchema("plugin_database")).postgres)
    val repository = PostgresPluginRepository(dataSource)
    val peer = PostgresPluginRepository(dataSource)
    val config = PluginRuntimeConfig(queueMillis = 5_000, callMillis = 5_000)
    val timing get() = config

    init {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("insert into users(id) values ('alice'), ('bob'), ('carol')") }
        }
    }

    suspend fun serviceLease(): PluginCallLease {
        val binding = PluginBinding("transport", "v1", "host-service-test".sha256Hex())
        repository.install(binding, 0)
        return requireNotNull(repository.claim("alice", binding, config))
    }

    suspend fun expireBreaker() = dataSource.write { connection ->
        connection.createStatement().use {
            it.executeUpdate("update jvm_plugin_calls set open_until = clock_timestamp() - interval '1 second' where owner_id = 'alice'")
        }
    }

    suspend fun awaitBlockedQuery() = withTimeout(5_000) {
        while (dataSource.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""select count(*) from pg_stat_activity where wait_event_type = 'Lock'
                    and query like '%select 1 from jvm_plugin_calls%'""").use { it.next(); it.getInt(1) }
            }
        } == 0) delay(10)
    }

    override fun close() = dataSource.close()
}
