package ru.souz.plugins.gmail

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID
import ru.souz.plugins.DataChange
import ru.souz.plugins.PluginData
import ru.souz.plugins.PluginException

internal enum class Mode { signal, subject, summary, assistant }
internal enum class Phase { needs_profile, ready, resync_required }
internal enum class ClaimPhase { pending, claimed, delivering, uncertain }
internal data class Destination(val channelType: String, val channelId: String)
internal data class Connection(val mailboxEmail: String, val topicName: String, val destination: Destination, val notificationMode: Mode = Mode.signal)
internal data class Claim(
    val status: ClaimPhase = ClaimPhase.pending,
    val claim: String? = null,
    val expiresAt: Long = 0,
    val prepared: Boolean = false,
    val text: String? = null,
)
internal data class GmailState(
    val revision: Long = 0,
    val status: Phase = Phase.needs_profile,
    val historyCursor: String? = null,
    val startedAtMillis: Long = 0,
    val pageToken: String? = null,
    val watchExpiresAt: Long = 0,
    val pending: Map<String, Claim> = emptyMap(),
    val done: List<String> = emptyList(),
) {
    fun expire(now: Long): GmailState = copy(pending = pending.mapValues { (_, item) ->
        if (item.expiresAt > now) item else when (item.status) {
            ClaimPhase.claimed -> Claim()
            ClaimPhase.delivering -> item.copy(status = ClaimPhase.uncertain)
            else -> item
        }
    })

    fun claimed(message: String, token: String): Claim {
        gmailRequire(status == Phase.ready, "mailbox_not_ready", "Sync a verified mailbox before processing claims")
        val item = pending[message]
        gmailRequire(item?.status == ClaimPhase.claimed && item.claim == token,
            "claim_inactive", "Claim is missing, expired or already processed; sync for an active claim")
        return requireNotNull(item)
    }

    fun finish(message: String): GmailState = copy(pending = pending - message, done = done + message)
    fun replace(message: String, item: Claim): GmailState = copy(pending = pending + (message to item))
}
internal data class GmailRecord(val connection: Connection, val state: GmailState)

internal class GmailStore(private val data: PluginData, private val now: () -> Long) {
    suspend fun <R> change(config: String, block: (Connection, GmailState) -> Pair<GmailState, R>): R = data.update(key(config)) { raw ->
        val record = raw?.let { json.treeToValue(it, GmailRecord::class.java) }
            ?: throw PluginException("gmail_connection_missing", "Configure or import this connection first")
        val expired = record.state.expire(now())
        val current = if (expired == record.state) expired else expired.copy(revision = record.state.revision + 1)
        val (next, output) = block(record.connection, current)
        val updated = if (next == record.state) record else record.copy(state = next.copy(revision = record.state.revision + 1))
        DataChange(tree(updated), output)
    }

    suspend fun snapshot(config: String): GmailRecord = change(config) { connection, state -> state to GmailRecord(connection, state) }

    suspend fun create(config: String, record: GmailRecord, allowIdentical: Boolean) = data.update(key(config)) { existing ->
        gmailRequire(existing == null || allowIdentical && json.treeToValue(existing, GmailRecord::class.java).connection == record.connection,
            "connection_exists", "Connection already exists; use another name")
        DataChange(existing ?: tree(record), Unit)
    }
}

internal fun validate(connection: Connection) {
    gmailRequire(connection.mailboxEmail.length <= 320 && '@' in connection.mailboxEmail,
        "invalid_connection", "mailboxEmail must contain @ and be at most 320 characters")
    gmailRequire(connection.topicName.matches(Regex("projects/[^/\\s]{1,128}/topics/[^/\\s]{1,128}")),
        "invalid_connection", "topicName must have the form projects/<project>/topics/<topic>, with segments up to 128 characters")
    gmailRequire(connection.destination.channelType.isNotBlank() && connection.destination.channelType.length <= 64,
        "invalid_connection", "channelType must be nonblank and at most 64 characters")
    gmailRequire(connection.destination.channelId.isNotBlank() && connection.destination.channelId.length <= 256,
        "invalid_connection", "channelId must be nonblank and at most 256 characters")
}

internal fun gmailRequire(valid: Boolean, code: String, message: String) {
    if (!valid) throw PluginException("gmail_$code", message)
}

internal fun claim(now: Long): Claim = Claim(ClaimPhase.claimed, UUID.randomUUID().toString(), now + 600_000)
private fun key(config: String): String {
    require(config.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) { "Invalid connection name" }
    return "connection:$config"
}

/** Python claims cannot be retried safely, even when their old state says claimed. */
internal fun importState(raw: JsonNode, connection: Connection): GmailState {
    require(raw.path("schemaVersion").asInt() == 1 && raw.text("mailboxEmail").equals(connection.mailboxEmail, ignoreCase = true))
    val cursor = raw.get("historyCursor")?.takeUnless { it.isNull }?.asText()?.let(::historyId)
    val started = raw.path("startedAtMillis").asLong()
    require(cursor == null || started > 0)
    require(raw.text("status") != "ready" || cursor != null)
    require(raw.path("revision").isIntegralNumber && raw.path("revision").asLong() >= 0)
    require(raw.path("pending").isObject && raw.path("pending").size() <= 500 && raw.path("done").isArray)
    val pending = raw.path("pending").properties().associate { (id, item) ->
        val status = ClaimPhase.valueOf(item.text("status"))
        require(status == ClaimPhase.pending || item.path("expiresAt").isNumber)
        messageId(id) to if (status == ClaimPhase.pending) Claim() else Claim(
            ClaimPhase.uncertain, item.text("claim"), (item.path("expiresAt").asDouble() * 1000).toLong(),
        )
    }
    require(raw.text("status") in LEGACY_PHASES)
    val phase = when {
        raw.path("historyGap").asBoolean() || raw.text("status") == "resync_required" -> Phase.resync_required
        cursor != null -> Phase.ready
        else -> Phase.needs_profile
    }
    return GmailState(raw.path("revision").asLong(), phase, cursor, started,
        raw.get("pageToken")?.takeUnless { it.isNull }?.asText(), raw.path("watchExpiresAt").asLong(), pending,
        raw.path("done").map { messageId(it.asText()) })
}

private val LEGACY_PHASES = setOf("needs_profile", "ready", "resync_required", "reconnect_required", "account_mismatch")
