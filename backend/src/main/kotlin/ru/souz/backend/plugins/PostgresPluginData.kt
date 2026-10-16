package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.JsonNode
import javax.sql.DataSource
import ru.souz.backend.storage.postgres.postgresStorageMapper
import ru.souz.backend.storage.postgres.read
import ru.souz.backend.storage.postgres.setJson
import ru.souz.backend.storage.postgres.write
import ru.souz.plugins.PLUGIN_MAX_BYTES
import ru.souz.plugins.PluginData
import ru.souz.plugins.PluginDataPage
import ru.souz.plugins.PluginDataRecord

internal class PostgresPluginData(
    private val source: DataSource,
    private val userId: String,
    private val pluginId: String,
) : PluginData {
    override suspend fun get(key: String): JsonNode? {
        validateKey(key)
        return source.read { connection ->
            connection.prepareStatement("select value from plugin_data where user_id = ? and plugin_id = ? and record_key = ?").use { statement ->
                statement.setString(1, userId)
                statement.setString(2, pluginId)
                statement.setString(3, key)
                statement.executeQuery().use { result ->
                    if (result.next()) postgresStorageMapper.readTree(result.getString(1)) else null
                }
            }
        }
    }

    override suspend fun put(key: String, value: JsonNode) {
        update(key) { value }
    }

    override suspend fun update(key: String, transform: (JsonNode?) -> JsonNode): JsonNode {
        validateKey(key)
        return source.write { connection ->
            connection.prepareStatement("""
                insert into plugin_data(user_id, plugin_id, record_key, value) values (?, ?, ?, 'null'::jsonb)
                on conflict (user_id, plugin_id, record_key) do nothing
            """.trimIndent()).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, pluginId)
                statement.setString(3, key)
                val inserted = statement.executeUpdate() == 1
                connection.prepareStatement("select value from plugin_data where user_id = ? and plugin_id = ? and record_key = ? for update").use { select ->
                    select.setString(1, userId)
                    select.setString(2, pluginId)
                    select.setString(3, key)
                    select.executeQuery().use { result ->
                        check(result.next()) { "Plugin record disappeared during update" }
                        val value = transform(if (inserted) null else postgresStorageMapper.readTree(result.getString(1)))
                        val json = postgresStorageMapper.writeValueAsBytes(value)
                        require(json.size <= PLUGIN_MAX_BYTES) { "Plugin record exceeds 1 MiB" }
                        connection.prepareStatement("update plugin_data set value = ?, updated_at = now() where user_id = ? and plugin_id = ? and record_key = ?").use { update ->
                            update.setJson(1, json.toString(Charsets.UTF_8))
                            update.setString(2, userId)
                            update.setString(3, pluginId)
                            update.setString(4, key)
                            check(update.executeUpdate() == 1)
                        }
                        value.deepCopy<JsonNode>()
                    }
                }
            }
        }
    }

    override suspend fun delete(key: String): Boolean {
        validateKey(key)
        return source.write { connection ->
            connection.prepareStatement("delete from plugin_data where user_id = ? and plugin_id = ? and record_key = ?").use { statement ->
                statement.setString(1, userId)
                statement.setString(2, pluginId)
                statement.setString(3, key)
                statement.executeUpdate() != 0
            }
        }
    }

    override suspend fun list(after: String?, limit: Int): PluginDataPage {
        require(limit in 1..100) { "Plugin list limit must be 1..100" }
        after?.let(::validateKey)
        val records = source.read { connection ->
            connection.prepareStatement("""
                select record_key, value from plugin_data
                where user_id = ? and plugin_id = ? and record_key > ? collate "C"
                order by record_key limit ?
            """.trimIndent()).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, pluginId)
                statement.setString(3, after.orEmpty())
                statement.setInt(4, limit + 1)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(PluginDataRecord(result.getString(1), postgresStorageMapper.readTree(result.getString(2))))
                    }
                }
            }
        }
        return PluginDataPage(records.take(limit), records.getOrNull(limit)?.let { records[limit - 1].key })
    }

    private fun validateKey(key: String) {
        require(key.toByteArray().size in 1..512 && '\u0000' !in key) { "Invalid plugin record key" }
    }
}
