package ru.souz.backend.storage.postgres

import java.sql.Connection
import java.util.UUID

/** Serialize cleanup with execution admission. Active executions stay protected while waiting for input. */
internal fun Connection.lockPreparedAttachmentOwner(userId: String, shared: Boolean = false) {
    val function = if (shared) "pg_advisory_xact_lock_shared" else "pg_advisory_xact_lock"
    prepareStatement("select $function(hashtextextended(?, 976))").use { statement ->
        statement.setString(1, userId)
        statement.executeQuery().close()
    }
}

internal fun Connection.tryLockPreparedAttachmentOwner(userId: String): Boolean =
    prepareStatement("select pg_try_advisory_xact_lock(hashtextextended(?, 976))").use { statement ->
        statement.setString(1, userId)
        statement.executeQuery().use { it.next() && it.getBoolean(1) }
    }

// Legacy executions without a marker had no managed mount. Waiting options are durable across that boundary.
internal fun Connection.hasActiveAttachmentOwnerExecution(userId: String, isAlive: (UUID) -> Boolean): Boolean =
    prepareStatement(
        """
        select distinct case when status = 'waiting_option' then null else attachment_runtime_id end as runtime
        from agent_executions
        where user_id = ? and status in ('queued', 'running', 'waiting_option', 'cancelling')
          and (status = 'waiting_option' or attachment_runtime_id is not null)
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, userId)
        statement.executeQuery().use { rows ->
            while (rows.next()) {
                val runtime = rows.getObject(1, UUID::class.java)
                if (runtime == null || isAlive(runtime)) return true
            }
            false
        }
    }
