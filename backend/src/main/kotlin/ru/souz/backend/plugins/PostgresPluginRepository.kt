package ru.souz.backend.plugins

import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource
import com.fasterxml.jackson.databind.DeserializationFeature
import ru.souz.backend.storage.postgres.read
import ru.souz.backend.storage.postgres.write
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.api.PluginApi
import ru.souz.plugins.api.PluginState
import ru.souz.plugins.api.PluginStatePage
import ru.souz.plugins.api.PluginStateRecord

/** All ownership decisions use the database clock under short row locks. */
internal class PostgresPluginRepository(private val source: DataSource) {
    suspend fun registry(): List<PluginRegistration> = source.read { it.registry() }

    suspend fun install(binding: PluginBinding, revision: Long): PluginRegistration {
        requireIdentity(binding)
        return mutation(binding.id, revision, create = true) { db ->
            db.change("insert into jvm_plugin_versions(plugin_id, version, digest) values (?, ?, ?) on conflict do nothing",
                binding.id, binding.version, binding.digest)
            val version = db.version(binding.id, binding.version) ?: registryError("plugin_version_conflict")
            if (version.binding != binding) registryError("plugin_version_conflict")
            db.select(version)
        }
    }

    suspend fun select(id: String, version: String, revision: Long) = mutation(id, revision) {
        it.select(it.version(id, version) ?: registryError("plugin_version_not_found"))
    }
    suspend fun disable(id: String, revision: Long) = mutation(id, revision) {
        it.change("update jvm_plugins set enabled = false where plugin_id = ?", id)
    }
    suspend fun retire(id: String, version: String, revision: Long) = mutation(id, revision) {
        val target = it.version(id, version) ?: registryError("plugin_version_not_found")
        it.change("update jvm_plugin_versions set retired = true where plugin_id = ? and version = ?", id, version)
        it.change("update jvm_plugins set default_digest = null where plugin_id = ? and default_digest = ?", id, target.binding.digest)
    }
    suspend fun reenable(id: String, version: String, revision: Long) = mutation(id, revision) {
        if (it.change("update jvm_plugin_versions set quarantined = false, severe_incidents = 0 where plugin_id = ? and version = ?",
                id, version) != 1) registryError("plugin_version_not_found")
    }
    suspend fun version(binding: PluginBinding): PluginVersion? = source.read { it.version(binding.id, binding.version)?.takeIf { v -> v.binding == binding } }

    suspend fun claim(owner: String, binding: PluginBinding, timing: PluginRuntimeConfig): PluginCallLease? = source.write { db ->
        require(owner.isNotBlank())
        // Operator bans and admission are ordered on the parent row across every version.
        val enabled = db.rows("select enabled from jvm_plugins where plugin_id = ? for share", binding.id) { it.getBoolean(1) }
            .singleOrNull() ?: unavailable("plugin_version_not_found")
        if (!enabled) unavailable("plugin_disabled")
        val version = db.version(binding.id, binding.version)?.takeIf { it.binding == binding } ?: unavailable("plugin_version_not_found")
        if (version.quarantined) unavailable("plugin_quarantined")
        db.change("insert into jvm_plugin_calls(owner_id, plugin_id) values (?, ?) on conflict do nothing", owner, binding.id)
        db.lockCall(owner, binding.id)
        val (occupied, open) = db.rows("""
            select lease_until > clock_timestamp(), open_until > clock_timestamp()
            from jvm_plugin_calls where owner_id = ? and plugin_id = ?
        """, owner, binding.id) { it.getBoolean(1) to it.getBoolean(2) }.single()
        if (open) unavailable("plugin_user_circuit_open")
        if (occupied) return@write null
        PluginCallLease(owner, binding).also { lease ->
            db.change("""
                update jvm_plugin_calls set token = ?, digest = ?, recorded = false,
                    lease_until = clock_timestamp() + ? * interval '1 millisecond'
                where owner_id = ? and plugin_id = ?
            """, lease.token, binding.digest, timing.leaseMillis, owner, binding.id)
        }
    }

    suspend fun renew(lease: PluginCallLease, millis: Long): Boolean = source.write { db ->
        require(millis > 0)
        if (!db.owns(lease)) return@write false
        db.change("update jvm_plugin_calls set lease_until = clock_timestamp() + ? * interval '1 millisecond' where owner_id = ? and plugin_id = ?",
            millis, lease.ownerId, lease.binding.id)
        true
    }
    suspend fun release(lease: PluginCallLease) { source.write { db ->
        if (db.owns(lease)) db.change("update jvm_plugin_calls set token = null, digest = null, lease_until = null where owner_id = ? and plugin_id = ?",
            lease.ownerId, lease.binding.id)
    } }
    suspend fun assertOwned(lease: PluginCallLease) { source.write { if (!it.owns(lease)) unavailable("plugin_lease_lost") } }

    /** A timed-out handler keeps renewing its lease, but its outcome is counted once. */
    suspend fun recordOutcome(lease: PluginCallLease, outcome: PluginCallOutcome, timing: PluginRuntimeConfig) {
        source.write { db ->
            if (!db.owns(lease)) return@write
            if (outcome == PluginCallOutcome.IGNORED) {
                db.change("update jvm_plugin_calls set recorded = true where owner_id = ? and plugin_id = ?", lease.ownerId, lease.binding.id)
                return@write
            }
            val failed = outcome == PluginCallOutcome.TECHNICAL_FAILURE
            db.change("""
                update jvm_plugin_calls set recorded = true,
                    failures = case when ? then least(failures::bigint + 1, 2147483647) else 0 end,
                    open_until = case when not ? then null
                        when open_until is not null or failures::bigint + 1 >= ?
                        then clock_timestamp() + ? * interval '1 millisecond' else null end
                where owner_id = ? and plugin_id = ? and not recorded
            """, failed, failed, timing.userFailureThreshold, timing.cooldownMillis, lease.ownerId, lease.binding.id)
        }
    }

    fun state(lease: PluginCallLease): PluginState = object : PluginState {
        override suspend fun get(key: String): String? = access(key) {
            it.rows("select value::text from jvm_plugin_state where owner_id = ? and plugin_id = ? and state_key = ?",
                lease.ownerId, lease.binding.id, key) { row -> row.getString(1) }.singleOrNull()
        }
        override suspend fun put(key: String, json: String) {
            require(json.toByteArray().size <= PluginApi.MAX_JSON_BYTES)
            require(!restJsonMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json).isMissingNode)
            access(key) {
                it.change("""
                    insert into jvm_plugin_state(owner_id, plugin_id, state_key, value) values (?, ?, ?, ?::json)
                    on conflict (owner_id, plugin_id, state_key) do update set value = excluded.value
                """, lease.ownerId, lease.binding.id, key, json)
            }
        }
        override suspend fun delete(key: String): Boolean = access(key) {
            it.change("delete from jvm_plugin_state where owner_id = ? and plugin_id = ? and state_key = ?",
                lease.ownerId, lease.binding.id, key) == 1
        }
        override suspend fun list(after: String?, limit: Int): PluginStatePage {
            require(limit in 1..100)
            return access(after ?: "start") { db ->
                val records = db.rows("""
                    select state_key, value::text from jvm_plugin_state where owner_id = ? and plugin_id = ?
                    and state_key > ? collate "C" order by state_key limit ?
                """, lease.ownerId, lease.binding.id, after.orEmpty(), limit + 1) { PluginStateRecord(it.getString(1), it.getString(2)) }
                PluginStatePage(records.take(limit), if (records.size > limit) records[limit - 1].key else null)
            }
        }
        private suspend fun <T> access(key: String, action: (Connection) -> T): T {
            require(key.toByteArray().size in 1..512 && '\u0000' !in key)
            return source.write { db -> if (!db.owns(lease)) unavailable("plugin_lease_lost"); action(db) }
        }
    }

    suspend fun recordSevereIncident(binding: PluginBinding, incident: String, threshold: Int, code: String = "plugin_call_stalled", replica: String = "operator") {
        require(threshold > 0 && incident.length in 1..256 && code.matches(Regex("[a-z][a-z0-9_]{0,79}")))
        source.write { db ->
            db.lockPlugin(binding.id)
            require(db.version(binding.id, binding.version)?.binding == binding)
            if (db.change("""
                insert into jvm_plugin_incidents(plugin_id, digest, incident_id, reason_code, replica_id)
                values (?, ?, ?, ?, ?) on conflict do nothing
            """, binding.id, binding.digest, incident, code, replica) == 0) return@write
            db.change("""
                update jvm_plugin_versions set severe_incidents = least(severe_incidents::bigint + 1, 2147483647),
                    quarantined = quarantined or severe_incidents::bigint + 1 >= ? where plugin_id = ? and digest = ?
            """, threshold, binding.id, binding.digest)
            db.change("update jvm_plugins set revision = revision + 1 where plugin_id = ?", binding.id)
        }
    }

    private suspend fun mutation(id: String, revision: Long, create: Boolean = false, action: (Connection) -> Unit): PluginRegistration {
        if (revision < 0) registryError("plugin_invalid_revision")
        return source.write { db ->
            if (create) db.change("insert into jvm_plugins(plugin_id) values (?) on conflict do nothing", id)
            if (db.lockPlugin(id) != revision) registryError("plugin_revision_conflict")
            action(db)
            db.change("update jvm_plugins set revision = revision + 1 where plugin_id = ?", id)
            db.registry(id).single()
        }
    }

    private fun Connection.registry(id: String? = null): List<PluginRegistration> = rows("""
        select p.plugin_id, p.default_digest, p.enabled, p.revision, v.version, v.digest, v.retired, v.quarantined, v.severe_incidents
        from jvm_plugins p left join jvm_plugin_versions v using (plugin_id)
    """ + (if (id == null) "" else " where p.plugin_id = ?") + " order by p.plugin_id, v.version", *listOfNotNull(id).toTypedArray()) {
        PluginRegistration(it.getString("plugin_id"), it.getString("default_digest"), it.getBoolean("enabled"),
            it.getLong("revision"), emptyList()) to it.getString("version")?.let { _ -> it.version() }
    }.groupBy { it.first }.map { (row, versions) -> row.copy(versions = versions.mapNotNull { it.second }) }

    private fun Connection.select(version: PluginVersion) {
        if (version.quarantined) registryError("plugin_quarantined")
        change("update jvm_plugin_versions set retired = false where plugin_id = ? and version = ?", version.binding.id, version.binding.version)
        change("update jvm_plugins set default_digest = ?, enabled = true where plugin_id = ?", version.binding.digest, version.binding.id)
    }
    private fun Connection.version(id: String, version: String): PluginVersion? =
        rows("select * from jvm_plugin_versions where plugin_id = ? and version = ?", id, version) { it.version() }.singleOrNull()
    private fun Connection.lockPlugin(id: String): Long =
        rows("select revision from jvm_plugins where plugin_id = ? for update", id) { it.getLong(1) }.singleOrNull() ?: registryError("plugin_not_found")
    private fun Connection.lockCall(owner: String, id: String) {
        rows("select 1 from jvm_plugin_calls where owner_id = ? and plugin_id = ? for update", owner, id) { it.getInt(1) }
    }
    private fun Connection.owns(lease: PluginCallLease): Boolean {
        lockCall(lease.ownerId, lease.binding.id)
        // Evaluate expiry AFTER acquiring the row lock, including when the tuple was not updated.
        return rows("""
            select 1 from jvm_plugin_calls where owner_id = ? and plugin_id = ? and token = ? and digest = ?
                and lease_until > clock_timestamp()
        """, lease.ownerId, lease.binding.id, lease.token, lease.binding.digest) { true }.isNotEmpty()
    }
}

private fun ResultSet.version() = PluginVersion(PluginBinding(getString("plugin_id"), getString("version"), getString("digest")),
    getBoolean("retired"), getBoolean("quarantined"), getInt("severe_incidents"))
private fun Connection.statement(sql: String, values: Array<out Any?>) = prepareStatement(sql.trimIndent()).apply {
    queryTimeout = 5
    values.forEachIndexed { i, value -> setObject(i + 1, value) }
}
private fun Connection.change(sql: String, vararg values: Any?): Int = statement(sql, values).use { it.executeUpdate() }
private fun <T> Connection.rows(sql: String, vararg values: Any?, read: (ResultSet) -> T): List<T> =
    statement(sql, values).use { it.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } } }
internal fun registryError(code: String): Nothing = throw PluginRegistryException(code)
internal fun unavailable(code: String): Nothing = throw PluginUnavailableException(code)
internal fun requireIdentity(binding: PluginBinding) {
    if (!binding.id.matches(Regex("[a-z][a-z0-9_]{0,39}")) ||
        !binding.version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) ||
        !binding.digest.matches(Regex("[a-f0-9]{64}"))) registryError("plugin_invalid_identity")
}
