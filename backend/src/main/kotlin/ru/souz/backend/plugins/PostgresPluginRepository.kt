package ru.souz.backend.plugins

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource
import ru.souz.backend.storage.postgres.read
import ru.souz.backend.storage.postgres.write
import ru.souz.backend.storage.postgres.setJson
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.api.PluginState
import ru.souz.plugins.api.PluginStateValue

internal data class DesiredPlugin(val id: String, val digest: String, val enabled: Boolean, val revision: Long)

/** Operator writes and runtime reads use the same authoritative PostgreSQL registry. No HTTP upload path. */
internal class PostgresPluginRepository(private val dataSource: DataSource) {
    suspend fun install(bytes: ByteArray): PluginArtifact {
        val artifact = PluginArtifact.inspect(bytes)
        dataSource.write { connection ->
            connection.pluginStatement("""
                insert into skill_plugin_artifacts(plugin_id, version, digest, artifact) values (?, ?, ?, ?)
                on conflict (plugin_id, version) do nothing
            """.trimIndent()).use { statement ->
                statement.setString(1, artifact.id)
                statement.setString(2, artifact.version)
                statement.setString(3, artifact.digest)
                statement.setBytes(4, bytes)
                statement.executeUpdate()
            }
            require(findDigest(connection, artifact.id, artifact.version) == artifact.digest) {
                "Plugin ID/version already contains a different artifact."
            }
        }
        return artifact
    }

    suspend fun select(id: String, version: String, expectedRevision: Long): DesiredPlugin = dataSource.write { connection ->
        require(expectedRevision >= 0) { "Expected revision must be non-negative." }
        val digest = requireNotNull(findDigest(connection, id, version)) { "Plugin version is not installed." }
        val sql = if (expectedRevision == 0L) """
            insert into skill_plugin_desired(digest, plugin_id, enabled, revision) values (?, ?, true, 1)
            on conflict (plugin_id) do nothing returning plugin_id, digest, enabled, revision
        """ else """
            update skill_plugin_desired set digest = ?, enabled = true, revision = revision + 1, updated_at = now()
            where plugin_id = ? and revision = ? returning plugin_id, digest, enabled, revision
        """
        connection.pluginStatement(sql.trimIndent()).use { statement ->
            statement.setString(1, digest)
            statement.setString(2, id)
            if (expectedRevision > 0) statement.setLong(3, expectedRevision)
            statement.executeQuery().use { result ->
                require(result.next()) { "Desired revision conflict." }
                result.desired()
            }
        }
    }

    suspend fun disable(id: String, expectedRevision: Long): DesiredPlugin = dataSource.write { connection ->
        connection.pluginStatement("""
            update skill_plugin_desired set enabled = false, revision = revision + 1, updated_at = now()
            where plugin_id = ? and revision = ? returning plugin_id, digest, enabled, revision
        """.trimIndent()).use { statement ->
            statement.setString(1, id)
            statement.setLong(2, expectedRevision)
            statement.executeQuery().use { result ->
                require(result.next()) { "Desired revision conflict or plugin is not selected." }
                result.desired()
            }
        }
    }

    suspend fun desired(): List<DesiredPlugin> = dataSource.read { connection ->
        connection.pluginStatement("select plugin_id, digest, enabled, revision from skill_plugin_desired order by plugin_id").use {
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.desired()) } }
        }
    }

    suspend fun artifact(id: String, digest: String): ByteArray = dataSource.read { connection ->
        connection.pluginStatement("select artifact from skill_plugin_artifacts where plugin_id = ? and digest = ?").use {
            it.setString(1, id)
            it.setString(2, digest)
            it.executeQuery().use { rows ->
                require(rows.next()) { "Plugin artifact is missing." }
                rows.getBytes("artifact")
            }
        }
    }

    /** Only host code can bind a partition; plugin code receives no owner/plugin selector. */
    fun state(pluginId: String, ownerId: String): PluginState {
        require(PluginArtifact.ID.matches(pluginId) && ownerId.isNotBlank())
        return object : PluginState {
            override suspend fun get(key: String): PluginStateValue? {
                validateKey(key)
                return dataSource.read { connection ->
                    connection.pluginStatement("""
                        select value::text, data_schema_version, revision from skill_plugin_state
                        where plugin_id = ? and owner_id = ? and state_key = ?
                    """.trimIndent()).use {
                        it.setString(1, pluginId)
                        it.setString(2, ownerId)
                        it.setString(3, key)
                        it.executeQuery().use { rows -> if (rows.next()) rows.stateValue() else null }
                    }
                }
            }

            override suspend fun put(key: String, json: String, dataSchemaVersion: Int, expectedRevision: Long?): PluginStateValue? {
                validateKey(key)
                require(json.toByteArray().size <= 65_536 && dataSchemaVersion > 0) { "Invalid state size or schema version." }
                requireNotNull(restJsonMapper.readTree(json)) { "State must be valid JSON." }
                require(expectedRevision == null || expectedRevision >= 0) { "Invalid state revision." }
                return dataSource.write { connection ->
                    val update = expectedRevision != null && expectedRevision > 0
                    val sql = if (update) """
                        update skill_plugin_state set value = ?, data_schema_version = ?, revision = revision + 1
                        where plugin_id = ? and owner_id = ? and state_key = ? and revision = ?
                        returning value::text, data_schema_version, revision
                    """ else """
                        insert into skill_plugin_state(value, data_schema_version, plugin_id, owner_id, state_key, revision)
                        values (?, ?, ?, ?, ?, 1)
                        on conflict (plugin_id, owner_id, state_key) do update
                        set value = excluded.value, data_schema_version = excluded.data_schema_version,
                            revision = skill_plugin_state.revision + 1
                        where ? returning value::text, data_schema_version, revision
                    """
                    connection.pluginStatement(sql.trimIndent()).use {
                        it.setJson(1, json)
                        it.setInt(2, dataSchemaVersion)
                        it.setString(3, pluginId)
                        it.setString(4, ownerId)
                        it.setString(5, key)
                        if (update) it.setLong(6, expectedRevision) else it.setBoolean(6, expectedRevision == null)
                        it.executeQuery().use { rows -> if (rows.next()) rows.stateValue() else null }
                    }
                }
            }
        }
    }

    private fun validateKey(key: String) {
        require(key.toByteArray().size in 1..256 && '\u0000' !in key) { "Invalid state key." }
    }

    private fun findDigest(connection: Connection, id: String, version: String): String? =
        connection.pluginStatement("select digest from skill_plugin_artifacts where plugin_id = ? and version = ?").use {
            it.setString(1, id)
            it.setString(2, version)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString("digest") else null }
        }
}

private fun Connection.pluginStatement(sql: String): PreparedStatement = prepareStatement(sql).apply { queryTimeout = 30 }

private fun ResultSet.desired() = DesiredPlugin(getString("plugin_id"), getString("digest"), getBoolean("enabled"), getLong("revision"))
private fun ResultSet.stateValue() = PluginStateValue(getString("value"), getInt("data_schema_version"), getLong("revision"))
