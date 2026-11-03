package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.DeserializationFeature
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.storage.postgres.postgresStorageMapper
import ru.souz.backend.storage.postgres.read
import ru.souz.backend.storage.postgres.write
import ru.souz.plugins.api.PluginState
import ru.souz.plugins.api.PluginStatePage
import ru.souz.plugins.api.PluginStateRecord

/** Short database transactions own registry changes, user/plugin admission and fenced state writes. */
class PostgresPluginRepository(private val source: DataSource) {
    suspend fun registry(): List<PluginRegistration> = source.read { registrations(it) }

    suspend fun install(binding: PluginBinding, expectedRevision: Long): PluginRegistration {
        validate(binding)
        requireRevision(expectedRevision)
        return source.write { connection ->
            val created = connection.change("""
                insert into jvm_plugins(plugin_id, revision) values (?, 1) on conflict do nothing
            """, binding.id) == 1
            val revision = connection.lockPlugin(binding.id)
            if (if (created) expectedRevision != 0L else expectedRevision != revision) conflict()
            connection.change("""
                insert into jvm_plugin_versions(plugin_id, version, digest) values (?, ?, ?)
                on conflict (plugin_id, version) do nothing
            """, binding.id, binding.version, binding.digest)
            val version = connection.findVersion(binding.id, binding.version)
                ?: throw PluginRegistryException("plugin_version_not_found")
            if (version.binding.digest != binding.digest) throw PluginRegistryException("plugin_version_conflict")
            connection.selectVersion(version)
            if (!created) connection.bump(binding.id)
            registrations(connection, binding.id).single()
        }
    }

    suspend fun select(id: String, version: String, expectedRevision: Long): PluginRegistration =
        mutate(id, expectedRevision) { connection ->
            connection.selectVersion(connection.findVersion(id, version)
                ?: throw PluginRegistryException("plugin_version_not_found"))
        }

    suspend fun disable(id: String, expectedRevision: Long): PluginRegistration = mutate(id, expectedRevision) {
        it.change("update jvm_plugins set enabled = false where plugin_id = ?", id)
    }

    suspend fun retire(id: String, version: String, expectedRevision: Long): PluginRegistration =
        mutate(id, expectedRevision) { connection ->
            val target = connection.findVersion(id, version) ?: throw PluginRegistryException("plugin_version_not_found")
            connection.change("update jvm_plugin_versions set retired = true where plugin_id = ? and version = ?", id, version)
            connection.change("update jvm_plugins set default_digest = null where plugin_id = ? and default_digest = ?",
                id, target.binding.digest)
        }

    suspend fun reenable(id: String, version: String, expectedRevision: Long): PluginRegistration =
        mutate(id, expectedRevision) { connection ->
            if (connection.change("""
                update jvm_plugin_versions set quarantined = false, severe_incidents = 0
                where plugin_id = ? and version = ?
            """, id, version) != 1) throw PluginRegistryException("plugin_version_not_found")
            // Old incident identities remain deduplicated after an explicit reset.
        }

    suspend fun version(binding: PluginBinding): PluginVersion? = source.read { connection ->
        connection.findVersion(binding.id, binding.version)?.takeIf { it.binding == binding }
    }

    /** null means another invocation owns this user's plugin; retirement does not invalidate a pin. */
    suspend fun claim(owner: String, binding: PluginBinding, config: PluginRuntimeConfig): PluginCallLease? {
        require(owner.isNotBlank())
        validate(binding)
        return source.write { connection ->
            // Keep admission ordered against operator disable/quarantine until this short transaction commits.
            val enabled = connection.rows("select enabled from jvm_plugins where plugin_id = ? for share", binding.id) {
                it.getBoolean(1)
            }.singleOrNull() ?: throw PluginUnavailableException("plugin_version_not_found")
            if (!enabled) throw PluginUnavailableException("plugin_disabled")
            val version = connection.findVersion(binding.id, binding.version)?.takeIf { it.binding == binding }
                ?: throw PluginUnavailableException("plugin_version_not_found")
            if (version.quarantined) throw PluginUnavailableException("plugin_quarantined")
            connection.change("""
                insert into jvm_plugin_calls(owner_id, plugin_id) values (?, ?) on conflict do nothing
            """, owner, binding.id)
            val row = requireNotNull(connection.lockCall(owner, binding.id))
            val now = connection.now()
            if (row.openUntil?.isAfter(now) == true) throw PluginUnavailableException("plugin_user_circuit_open")
            if (row.until?.isAfter(now) == true) return@write null
            val token = UUID.randomUUID()
            val expires = now.plusMillis(config.leaseMillis)
            connection.change("""
                update jvm_plugin_calls set token = ?, digest = ?, lease_until = ?, probe = ?, outcome_recorded = false
                where owner_id = ? and plugin_id = ?
            """, token, binding.digest, expires, row.openUntil != null, owner, binding.id)
            PluginCallLease(owner, binding.id, binding.digest, token, expires)
        }
    }

    suspend fun renew(lease: PluginCallLease, leaseMillis: Long): Boolean {
        require(leaseMillis > 0)
        return source.write { connection ->
            val row = connection.lockCall(lease.ownerId, lease.pluginId)
            val now = connection.now()
            if (row?.owns(lease, now) != true) return@write false
            connection.change("update jvm_plugin_calls set lease_until = ? where owner_id = ? and plugin_id = ?",
                now.plusMillis(leaseMillis), lease.ownerId, lease.pluginId)
            true
        }
    }

    suspend fun assertOwned(lease: PluginCallLease) {
        source.write { connection -> connection.requireOwned(lease) }
    }

    /** Records the caller's outcome once; actual work retains its independently renewable lease. */
    suspend fun recordOutcome(lease: PluginCallLease, outcome: PluginCallOutcome, config: PluginRuntimeConfig) {
        source.write { connection ->
            val row = connection.lockCall(lease.ownerId, lease.pluginId)
            val now = connection.now()
            if (row?.owns(lease, now) != true || row.recorded) return@write
            var failures = row.failures
            var openUntil = row.openUntil
            when (outcome) {
                PluginCallOutcome.SUCCESS -> { failures = 0; openUntil = null }
                PluginCallOutcome.TECHNICAL_FAILURE -> {
                    failures = (failures.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    if (row.probe || failures >= config.userFailureThreshold) openUntil = now.plusMillis(config.cooldownMillis)
                }
                PluginCallOutcome.IGNORED -> Unit
            }
            connection.change("""
                update jvm_plugin_calls set failure_count = ?, open_until = ?, outcome_recorded = true
                where owner_id = ? and plugin_id = ?
            """, failures, openUntil, lease.ownerId, lease.pluginId)
        }
    }

    suspend fun release(lease: PluginCallLease) {
        source.write { connection ->
            val row = connection.lockCall(lease.ownerId, lease.pluginId)
            if (row?.owns(lease, connection.now()) != true) return@write
            connection.change("""
                update jvm_plugin_calls set token = null, digest = null, lease_until = null, probe = false
                where owner_id = ? and plugin_id = ?
            """, lease.ownerId, lease.pluginId)
        }
    }

    fun state(lease: PluginCallLease): PluginState = object : PluginState {
        override suspend fun get(key: String): String? = access(key) { connection ->
            connection.rows("select value::text from jvm_plugin_state where owner_id = ? and plugin_id = ? and state_key = ?",
                lease.ownerId, lease.pluginId, key) { it.getString(1) }.singleOrNull()
        }

        override suspend fun put(key: String, json: String) {
            require(json.toByteArray().size <= MAX_STATE_BYTES) { "Plugin state exceeds 1 MiB." }
            val value = postgresStorageMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(json)
            require(value != null && !value.isMissingNode) { "Plugin state must be JSON." }
            access(key) { connection ->
                connection.change("""
                    insert into jvm_plugin_state(owner_id, plugin_id, state_key, value) values (?, ?, ?, ?::json)
                    on conflict (owner_id, plugin_id, state_key) do update set value = excluded.value
                """, lease.ownerId, lease.pluginId, key, json)
            }
        }

        override suspend fun delete(key: String): Boolean = access(key) { connection ->
            connection.change("delete from jvm_plugin_state where owner_id = ? and plugin_id = ? and state_key = ?",
                lease.ownerId, lease.pluginId, key) == 1
        }

        override suspend fun list(after: String?, limit: Int): PluginStatePage {
            require(limit in 1..100) { "Plugin state page size must be 1..100." }
            after?.let(::validateKey)
            return source.write { connection ->
                connection.requireOwned(lease)
                val records = connection.rows("""
                    select state_key, value::text from jvm_plugin_state where owner_id = ? and plugin_id = ?
                    and state_key collate "C" > ? collate "C" order by state_key collate "C" limit ?
                """, lease.ownerId, lease.pluginId, after.orEmpty(), limit + 1) {
                    PluginStateRecord(it.getString(1), it.getString(2))
                }
                PluginStatePage(records.take(limit), records.takeIf { it.size > limit }?.get(limit - 1)?.key)
            }
        }

        private suspend fun <T> access(key: String, action: (Connection) -> T): T {
            validateKey(key)
            return source.write { connection -> connection.requireOwned(lease); action(connection) }
        }
    }

    /** A version's severe counter survives process restarts and is reset only by explicit re-enable. */
    suspend fun recordSevereIncident(
        binding: PluginBinding,
        incidentId: String,
        threshold: Int,
        code: String = "plugin_severe_incident",
        replicaId: String? = null,
    ): Boolean {
        require(threshold > 0 && incidentId.isNotBlank() && incidentId.length <= 256)
        require(code.matches(Regex("[a-z][a-z0-9_]{0,79}"))) { "Invalid plugin incident reason code." }
        require(replicaId == null || replicaId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "Invalid plugin replica identity." }
        return source.write { connection ->
            connection.lockPlugin(binding.id)
            val version = connection.findVersion(binding.id, binding.version)?.takeIf { it.binding == binding }
                ?: throw PluginRegistryException("plugin_version_not_found")
            val added = connection.change("""
                insert into jvm_plugin_incidents(plugin_id, digest, incident_id, reason_code, replica_id)
                values (?, ?, ?, ?, ?) on conflict do nothing
            """, binding.id, binding.digest, incidentId, code, replicaId)
            if (added == 0) return@write version.quarantined
            connection.change("""
                update jvm_plugin_versions set severe_incidents = least(severe_incidents::bigint + 1, 2147483647),
                quarantined = quarantined or severe_incidents::bigint + 1 >= ? where plugin_id = ? and digest = ?
            """, threshold, binding.id, binding.digest)
            connection.bump(binding.id)
            requireNotNull(connection.findVersion(binding.id, binding.version)).quarantined
        }
    }

    private suspend fun mutate(id: String, expected: Long, action: (Connection) -> Unit): PluginRegistration {
        requireRevision(expected)
        return source.write { connection ->
            if (connection.lockPlugin(id) != expected) conflict()
            action(connection)
            connection.bump(id)
            registrations(connection, id).single()
        }
    }

    private fun registrations(connection: Connection, id: String? = null): List<PluginRegistration> =
        connection.rows("""
            select p.plugin_id, p.default_digest, p.enabled, p.revision,
                v.version, v.digest, v.retired, v.quarantined, v.severe_incidents
            from jvm_plugins p left join jvm_plugin_versions v on p.plugin_id = v.plugin_id
            ${if (id == null) "" else "where p.plugin_id = ?"} order by p.plugin_id, v.version
        """, *listOfNotNull(id).toTypedArray()) { row ->
            PluginRegistration(row.getString("plugin_id"), row.getString("default_digest"), row.getBoolean("enabled"),
                row.getLong("revision"), emptyList()) to row.getString("version")?.let { row.version() }
        }.groupBy { it.first.id }.values.map { rows ->
            rows.first().first.copy(versions = rows.mapNotNull { it.second })
        }

    private fun Connection.selectVersion(version: PluginVersion) {
        if (version.quarantined) throw PluginRegistryException("plugin_quarantined")
        change("update jvm_plugin_versions set retired = false where plugin_id = ? and version = ?",
            version.binding.id, version.binding.version)
        change("update jvm_plugins set default_digest = ?, enabled = true where plugin_id = ?",
            version.binding.digest, version.binding.id)
    }

    private fun Connection.findVersion(id: String, version: String): PluginVersion? = rows(
        "select * from jvm_plugin_versions where plugin_id = ? and version = ?", id, version,
    ) { it.version() }.singleOrNull()

    private fun Connection.lockPlugin(id: String): Long = rows(
        "select revision from jvm_plugins where plugin_id = ? for update", id,
    ) { it.getLong(1) }.singleOrNull() ?: throw PluginRegistryException("plugin_not_found")

    private fun Connection.bump(id: String) { change("update jvm_plugins set revision = revision + 1 where plugin_id = ?", id) }

    private fun Connection.requireOwned(lease: PluginCallLease) {
        val row = lockCall(lease.ownerId, lease.pluginId)
        // Check the database clock AFTER obtaining the row lock: waiting itself can outlast a lease.
        if (row?.owns(lease, now()) != true) throw PluginUnavailableException("plugin_lease_lost")
    }

    private fun Connection.lockCall(owner: String, id: String): CallRow? = rows(
        "select * from jvm_plugin_calls where owner_id = ? and plugin_id = ? for update", owner, id,
    ) { row ->
        CallRow(row.getObject("token", UUID::class.java), row.getString("digest"), row.instant("lease_until"),
            row.getInt("failure_count"), row.instant("open_until"), row.getBoolean("probe"), row.getBoolean("outcome_recorded"))
    }.singleOrNull()

    private data class CallRow(val token: UUID?, val digest: String?, val until: Instant?, val failures: Int,
        val openUntil: Instant?, val probe: Boolean, val recorded: Boolean) {
        fun owns(lease: PluginCallLease, now: Instant): Boolean =
            token == lease.token && digest == lease.digest && until?.isAfter(now) == true
    }

    private companion object {
        const val MAX_STATE_BYTES = 1_048_576
        fun requireRevision(value: Long) { if (value < 0) throw PluginRegistryException("plugin_invalid_revision") }
        fun conflict(): Nothing = throw PluginRegistryException("plugin_revision_conflict")
        fun validate(binding: PluginBinding) {
            if (!binding.id.matches(Regex("[a-z][a-z0-9_]{0,39}")) ||
                !binding.version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) ||
                !binding.digest.matches(Regex("[a-f0-9]{64}"))) throw PluginRegistryException("plugin_invalid_identity")
        }
        fun validateKey(key: String) {
            require(key.toByteArray().size in 1..512 && '\u0000' !in key) { "Invalid plugin state key." }
        }
    }
}

private fun ResultSet.version() = PluginVersion(PluginBinding(getString("plugin_id"), getString("version"), getString("digest")),
    getBoolean("retired"), getBoolean("quarantined"), getInt("severe_incidents"))
private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
private fun Connection.now(): Instant = rows("select clock_timestamp()") { it.getObject(1, OffsetDateTime::class.java).toInstant() }.single()
private fun Connection.statement(sql: String, values: Array<out Any?>): PreparedStatement = prepareStatement(sql.trimIndent()).apply {
    queryTimeout = 5
    values.forEachIndexed { index, value -> setObject(index + 1, if (value is Instant) value.atOffset(ZoneOffset.UTC) else value) }
}
private fun Connection.change(sql: String, vararg values: Any?): Int = statement(sql, values).use { it.executeUpdate() }
private fun <T> Connection.rows(sql: String, vararg values: Any?, read: (ResultSet) -> T): List<T> = statement(sql, values).use {
    it.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
}
