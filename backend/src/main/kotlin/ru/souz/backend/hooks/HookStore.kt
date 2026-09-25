package ru.souz.backend.hooks

import io.ktor.http.HttpStatusCode
import java.sql.ResultSet
import java.time.Instant
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
    val payloadHash: String,
    val prompt: String,
    val revision: String,
    val status: String,
    val errorCode: String?,
    val dispatchedAt: Instant?,
    val llmCalls: Int,
    val totalTokens: Long,
)

/** Concrete Postgres queue; no alternative storage or generic job framework. */
internal class HookStore(private val dataSource: DataSource, private val config: HookConfig) {
    suspend fun accept(snapshot: LoadedHook, payload: String, key: String?): Pair<HookReceipt, Boolean> = dataSource.write { c ->
        val hook = snapshot.definition
        // A user-row lock serializes admission and daily reservations across all of this owner's hooks.
        c.prepareStatement("select id from users where id = ? for update").use { s ->
            s.setString(1, hook.ownerUserId)
            s.executeQuery().use { if (!it.next()) throw hookError(503, "hook_owner_unavailable") }
        }
        val hash = sha256(payload.toByteArray())
        if (key != null) c.prepareStatement("select * from hook_receipts where hook_id = ? and event_key = ?").use { s ->
            s.setString(1, hook.hookId)
            s.setString(2, key)
            s.executeQuery().use { rows ->
                if (rows.next()) {
                    val existing = rows.receipt()
                    if (existing.userId != hook.ownerUserId || existing.payloadHash != hash) throw hookError(409, "idempotency_conflict")
                    return@write existing to true
                }
            }
        }
        c.prepareStatement("""
            select count(*) filter (where hook_id = ? and status in ('pending', 'running')) as pending,
                   count(*) filter (where created_at >= date_trunc('day', now() at time zone 'UTC') at time zone 'UTC') as today
            from hook_receipts where user_id = ?
        """.trimIndent()).use { s ->
            s.setString(1, hook.hookId)
            s.setString(2, hook.ownerUserId)
            s.executeQuery().use {
                it.next()
                if (it.getInt("pending") >= config.queuePerHook || it.getInt("today") >= config.eventsPerUserPerDay) {
                    throw hookError(429, "hook_admission_limit")
                }
            }
        }
        val chatId = UUID.randomUUID()
        c.prepareStatement("""
            insert into chats(id, user_id, client_type, request_id, payload_hash, title)
            values (?, ?, 'hook', ?, ?, ?)
        """.trimIndent()).use { s ->
            s.setObject(1, chatId)
            s.setString(2, hook.ownerUserId)
            s.setString(3, "internal:$chatId")
            s.setString(4, "internal:$chatId")
            s.setString(5, "Hook: ${hook.hookId}")
            s.executeUpdate()
        }
        c.prepareStatement("""
            insert into hook_receipts(id, hook_id, user_id, chat_id, event_key, payload_hash, payload, prompt, revision)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning *
        """.trimIndent()).use { s ->
            s.setObject(1, UUID.randomUUID())
            s.setString(2, hook.hookId)
            s.setString(3, hook.ownerUserId)
            s.setObject(4, chatId)
            s.setString(5, key)
            s.setString(6, hash)
            s.setString(7, payload)
            s.setString(8, hook.prompt)
            s.setString(9, snapshot.revision)
            s.executeQuery().use { it.next(); it.receipt() to false }
        }
    }

    suspend fun find(userId: String, id: UUID): HookReceipt? = dataSource.read { c ->
        c.prepareStatement("select * from hook_receipts where user_id = ? and id = ?").use { s ->
            s.setString(1, userId)
            s.setObject(2, id)
            s.executeQuery().use { if (it.next()) it.receipt() else null }
        }
    }

    suspend fun active(): List<HookReceipt> = dataSource.read { c ->
        c.prepareStatement("""
            select distinct on (hook_id) * from hook_receipts
            where status in ('pending', 'running') order by hook_id, ordinal
        """.trimIndent()).use { s ->
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.receipt()) } }
        }
    }

    suspend fun update(id: UUID, status: String, errorCode: String? = null) = dataSource.write { c ->
        c.prepareStatement("""
            update hook_receipts set status = ?, error_code = ?,
              dispatched_at = case when ? = 'running' then now() else dispatched_at end
            where id = ?
        """.trimIndent()).use { s ->
            s.setString(1, status)
            s.setString(2, errorCode)
            s.setString(3, status)
            s.setObject(4, id)
            s.executeUpdate()
        }
    }

    suspend fun recordTokens(id: UUID, tokens: Int) {
        if (tokens <= 0) return
        dataSource.write { c ->
            c.prepareStatement("update hook_receipts set total_tokens = total_tokens + ? where id = ?").use { s ->
                s.setInt(1, tokens)
                s.setObject(2, id)
                s.executeUpdate()
            }
        }
    }

    suspend fun reserveLlmCall(receipt: HookReceipt) = dataSource.write { c ->
        c.prepareStatement("select id from users where id = ? for update").use { s ->
            s.setString(1, receipt.userId)
            s.executeQuery().close()
        }
        c.prepareStatement("""
            insert into hook_daily_usage(user_id, day, llm_calls) values (?, (now() at time zone 'UTC')::date, 1)
            on conflict (user_id, day) do update set llm_calls = hook_daily_usage.llm_calls + 1
            where hook_daily_usage.llm_calls < ? returning llm_calls
        """.trimIndent()).use { s ->
            s.setString(1, receipt.userId)
            s.setInt(2, config.llmCallsPerUserPerDay)
            s.executeQuery().use { if (!it.next()) throw hookError(429, "hook_daily_llm_limit") }
        }
        c.prepareStatement("""
            update hook_receipts set llm_calls = llm_calls + 1
            where id = ? and status = 'running' and llm_calls < ? returning llm_calls
        """.trimIndent()).use { s ->
            s.setObject(1, receipt.id)
            s.setInt(2, config.llmCallsPerEvent)
            s.executeQuery().use { if (!it.next()) throw hookError(429, "hook_execution_llm_limit") }
        }
    }
}

private fun ResultSet.receipt() = HookReceipt(
    id = getObject("id", UUID::class.java), hookId = getString("hook_id"), userId = getString("user_id"),
    chatId = getObject("chat_id", UUID::class.java), payload = getString("payload"), payloadHash = getString("payload_hash"),
    prompt = getString("prompt"), revision = getString("revision"), status = getString("status"),
    errorCode = getString("error_code"), dispatchedAt = getTimestamp("dispatched_at")?.toInstant(),
    llmCalls = getInt("llm_calls"), totalTokens = getLong("total_tokens"),
)

internal fun hookError(status: Int, code: String) = BackendV1Exception(HttpStatusCode.fromValue(status), code, code.replace('_', ' ') + ".")
