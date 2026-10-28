package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.JsonNode
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource
import ru.souz.backend.storage.postgres.postgresStorageMapper
import ru.souz.backend.storage.postgres.read
import ru.souz.backend.storage.postgres.setJson
import ru.souz.backend.storage.postgres.write
import ru.souz.plugins.DataChange
import ru.souz.plugins.DataPage
import ru.souz.plugins.DataRecord
import ru.souz.plugins.PLUGIN_MAX_BYTES
import ru.souz.plugins.PluginData

internal class PostgresPluginData(private val source: DataSource, private val user: String, private val plugin: String) : PluginData {
    override suspend fun get(key: String): JsonNode? {
        validateKey(key)
        return source.read { it.readValue(key) }
    }

    override suspend fun <R> update(key: String, transform: (JsonNode?) -> DataChange<R>): R {
        validateKey(key)
        return source.write { connection ->
            // All writers, including delete and first insert, use the same transaction-scoped lock.
            connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))").use {
                it.setString(1, postgresStorageMapper.writeValueAsString(listOf(user, plugin, key)))
                it.execute()
            }
            val change = transform(connection.readValue(key))
            val value = change.value?.let(postgresStorageMapper::writeValueAsBytes)
            require(value == null || value.size <= PLUGIN_MAX_BYTES) { "Plugin record exceeds 1 MiB" }
            val sql = if (value == null) "delete from plugin_data where user_id = ? and plugin_id = ? and record_key = ?"
                else "insert into plugin_data(user_id, plugin_id, record_key, value) values (?, ?, ?, ?) " +
                    "on conflict (user_id, plugin_id, record_key) do update set value = excluded.value"
            connection.prepareStatement(sql).use {
                it.bind(key)
                if (value != null) it.setJson(4, value.toString(Charsets.UTF_8))
                it.executeUpdate()
            }
            change.result
        }
    }

    override suspend fun list(after: String?, limit: Int): DataPage {
        require(limit in 1..100)
        after?.let(::validateKey)
        return source.read { connection ->
            connection.prepareStatement("select record_key, value from plugin_data where user_id = ? and plugin_id = ? " +
                "and record_key > ? collate \"C\" order by record_key collate \"C\" limit ?").use { query ->
                query.bind(after.orEmpty())
                query.setInt(4, limit + 1)
                val records = query.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(DataRecord(rows.getString(1), postgresStorageMapper.readTree(rows.getString(2)))) }
                }
                DataPage(records.take(limit), records.takeIf { it.size > limit }?.get(limit - 1)?.key)
            }
        }
    }

    private fun Connection.readValue(key: String): JsonNode? =
        prepareStatement("select value from plugin_data where user_id = ? and plugin_id = ? and record_key = ?").use {
            it.bind(key)
            it.executeQuery().use { rows -> if (rows.next()) postgresStorageMapper.readTree(rows.getString(1)) else null }
        }

    private fun PreparedStatement.bind(key: String) {
        setString(1, user)
        setString(2, plugin)
        setString(3, key)
    }

    private fun validateKey(key: String) {
        require(key.toByteArray().size in 1..512 && '\u0000' !in key) { "Invalid plugin record key" }
    }
}
