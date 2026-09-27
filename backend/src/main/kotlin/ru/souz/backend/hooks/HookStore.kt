package ru.souz.backend.hooks

import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.storage.postgres.read
import ru.souz.backend.storage.postgres.write

internal data class HookReceipt(
    val id: UUID,
    val hookId: String,
    val userId: String,
    val chatId: UUID,
    val payload: String,
    val revision: String,
    val status: String,
    val errorCode: String?,
    val llmCalls: Int,
    val totalTokens: Long,
)

internal class HookStore(private val dataSource: DataSource, private val config: HookConfig) {
    suspend fun accept(snapshot: LoadedHook, payload: String, key: String?): HookAccepted = dataSource.write { c ->
        val hook = snapshot.definition
        // Serialize admission for this owner without blocking foreign-key checks on users.
        c.prepare("select id from users where id = ? for no key update", hook.ownerUserId).use { s ->
            s.executeQuery().use { if (!it.next()) throw hookError(503, "hook_owner_unavailable") }
        }
        val hash = sha256(payload.toByteArray())
        if (key != null) c.prepare("select id, user_id, payload_hash from hook_receipts where hook_id = ? and event_key = ?", hook.hookId, key).use { s ->
            s.executeQuery().use { rows ->
                if (rows.next()) {
                    if (rows.getString("user_id") != hook.ownerUserId || rows.getString("payload_hash") != hash) throw hookError(409, "idempotency_conflict")
                    return@write HookAccepted(rows.getObject("id", UUID::class.java), duplicate = true)
                }
            }
        }
        c.prepare("""
            select
              (select count(*) from hook_receipts where hook_id = ? and status in ('pending', 'running')) as pending,
              (select count(*) from hook_receipts where user_id = ?
                and created_at >= date_trunc('day', now() at time zone 'UTC') at time zone 'UTC') as today
        """, hook.hookId, hook.ownerUserId).use { s ->
            s.executeQuery().use {
                it.next()
                if (it.getInt("pending") >= config.queuePerHook || it.getInt("today") >= config.eventsPerUserPerDay) {
                    throw hookError(429, "hook_admission_limit")
                }
            }
        }
        val chatId = UUID.randomUUID()
        c.prepare("""
            insert into chats(id, user_id, client_type, request_id, payload_hash, title)
            values (?, ?, 'hook', ?, ?, ?)
        """, chatId, hook.ownerUserId, "internal:$chatId", "internal:$chatId", "Hook: ${hook.hookId}").use { s ->
            s.executeUpdate()
        }
        c.prepare("""
            insert into hook_receipts(id, hook_id, user_id, chat_id, event_key, payload_hash, payload, revision)
            values (?, ?, ?, ?, ?, ?, ?, ?) returning id
        """, UUID.randomUUID(), hook.hookId, hook.ownerUserId, chatId, key, hash, payload, snapshot.revision).use { s ->
            s.executeQuery().use { it.next(); HookAccepted(it.getObject("id", UUID::class.java), duplicate = false) }
        }
    }

    suspend fun find(userId: String, id: UUID): HookReceipt? = dataSource.read { c ->
        c.prepare("select * from hook_receipts where user_id = ? and id = ?", userId, id).use { s ->
            s.executeQuery().use { if (it.next()) it.receipt() else null }
        }
    }

    suspend fun latestByHookIds(userId: String, hookIds: Set<String>): Map<String, HookReceipt> {
        if (hookIds.isEmpty()) return emptyMap()
        return dataSource.read { c ->
            val ids = c.createArrayOf("text", hookIds.toTypedArray())
            try {
                c.prepareStatement("""
                    select distinct on (hook_id) * from hook_receipts
                    where user_id = ? and hook_id = any(?) order by hook_id, ordinal desc
                """.trimIndent()).use { s ->
                    s.setString(1, userId)
                    s.setArray(2, ids)
                    s.executeQuery().use { rows -> buildMap {
                        while (rows.next()) rows.receipt().let { put(it.hookId, it) }
                    } }
                }
            } finally { ids.free() }
        }
    }

    suspend fun active(): List<HookReceipt> = dataSource.read { c ->
        c.prepare("""
            select distinct on (hook_id) * from hook_receipts
            where status in ('pending', 'running') order by hook_id, ordinal
        """).use { s ->
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.receipt()) } }
        }
    }

    suspend fun update(id: UUID, status: String, errorCode: String? = null) = dataSource.write { c ->
        c.prepare("update hook_receipts set status = ?, error_code = ? where id = ?", status, errorCode, id).use { s ->
            s.executeUpdate()
        }
    }

    suspend fun recordTokens(id: UUID, tokens: Int) {
        if (tokens <= 0) return
        dataSource.write { c ->
            c.prepare("update hook_receipts set total_tokens = total_tokens + ? where id = ?", tokens, id).use { s ->
                s.executeUpdate()
            }
        }
    }

    suspend fun reserveLlmCall(receipt: HookReceipt) = dataSource.write { c ->
        c.prepare("""
            insert into hook_daily_usage(user_id, day, llm_calls) values (?, (now() at time zone 'UTC')::date, 1)
            on conflict (user_id, day) do update set llm_calls = hook_daily_usage.llm_calls + 1
            where hook_daily_usage.llm_calls < ? returning llm_calls
        """, receipt.userId, config.llmCallsPerUserPerDay).use { s ->
            s.executeQuery().use { if (!it.next()) throw hookError(429, "hook_daily_llm_limit") }
        }
        c.prepare("""
            update hook_receipts set llm_calls = llm_calls + 1
            where id = ? and status = 'running' and llm_calls < ? returning llm_calls
        """, receipt.id, config.llmCallsPerEvent).use { s ->
            s.executeQuery().use { if (!it.next()) throw hookError(429, "hook_execution_llm_limit") }
        }
    }
}

private fun Connection.prepare(sql: String, vararg values: Any?) = prepareStatement(sql.trimIndent()).apply {
    values.forEachIndexed { index, value -> setObject(index + 1, value) }
}

private fun ResultSet.receipt() = HookReceipt(
    id = getObject("id", UUID::class.java), hookId = getString("hook_id"), userId = getString("user_id"),
    chatId = getObject("chat_id", UUID::class.java), payload = getString("payload"),
    revision = getString("revision"), status = getString("status"),
    errorCode = getString("error_code"),
    llmCalls = getInt("llm_calls"), totalTokens = getLong("total_tokens"),
)

internal fun hookError(status: Int, code: String) = BackendV1Exception(HttpStatusCode.fromValue(status), code, code.replace('_', ' ') + ".")
