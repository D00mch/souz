package ru.souz.tool.mail

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.ShellException
import ru.souz.tool.ToolRunBashCommand
import java.util.UUID

/** Only the authenticated control-bot owner can consume a prepared email; no tool exposes approval. */
class MailSendApproval(
    private val bash: ToolRunBashCommand,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private var pending: Pending? = null

    private data class Pending(
        val token: String,
        val reply: Boolean,
        val args: List<String>,
        val snapshot: String,
        val preview: String,
        val createdAt: Long,
        val previewOffset: Int = 0,
        val presented: Boolean = false,
    )

    internal suspend fun request(reply: Boolean, args: List<String>): String = mutex.withLock {
        // A retry or changed message replaces the previous request, never its approval.
        pending = null
        val snapshot = bash.sh(MailAppleScriptCommands.approvalCommand(reply), *args.toTypedArray())
        val fields = restJsonMapper.readTree(snapshot)
        val keys = listOf("sender", "subject", "content", "to", "cc", "bcc")
        if (!fields.isObject || fields.fieldNames().asSequence().toSet() != keys.toSet() ||
            keys.take(3).any { !fields[it].isTextual } ||
            keys.drop(3).any { key -> !fields[key].isArray || fields[key].any { !it.isTextual || it.asText().isBlank() } } ||
            fields["to"].isEmpty
        ) throw BadInputException("Mail returned an incomplete message preview. Nothing was sent.")

        val preview = buildString {
            appendLine("Email to send")
            appendLine("From: ${fields["sender"].asText()}")
            for (key in keys.drop(3)) appendLine("${key.uppercase()}: ${fields[key].joinToString { it.asText() }}")
            appendLine("Subject: ${fields["subject"].asText()}")
            appendLine("Body:")
            append(fields["content"].asText())
        }
        val token = UUID.randomUUID().toString()
        pending = Pending(token, reply, args.toList(), snapshot, preview, elapsedMillis())
        "Nothing was sent. Review the exact email in Telegram with /mail_preview $token. " +
            "Only the owner can approve it after reviewing. This request expires in 10 minutes; a new request replaces it."
    }

    /** Called for raw text only, after Telegram owner/private-chat authorization, outside the agent. */
    suspend fun handleOwnerCommand(text: String, show: suspend (String) -> Unit): Boolean {
        val parts = text.trim().split(Regex("\\s+"))
        when (parts.first()) {
            "/mail_preview", "/mail_send", "/mail_cancel" -> Unit
            else -> return false
        }
        val toSend = mutex.withLock {
            val request = pending
            if (request != null && elapsedMillis() - request.createdAt >= 10 * 60 * 1_000L) pending = null
            if (request == null || pending == null || parts.size != 2 || parts[1] != request.token) {
                show("No matching pending email, or the request expired. Nothing was sent.")
                return true
            }
            when (parts[0]) {
                "/mail_preview" -> {
                    // Send every field through the host, including long bodies, before enabling approval.
                    var start = if (request.presented) 0 else request.previewOffset
                    pending = request.copy(previewOffset = start, presented = false)
                    while (start < request.preview.length) {
                        var end = (start + 3_500).coerceAtMost(request.preview.length)
                        if (end < request.preview.length && request.preview[end - 1].isHighSurrogate()) end--
                        // Pace even the first chunk after another control-bot response or failed attempt.
                        delay(1_000)
                        show(request.preview.substring(start, end))
                        start = end
                        // A failed or rate-limited delivery resumes at the first unacknowledged chunk.
                        pending = request.copy(previewOffset = start, presented = false)
                    }
                    delay(1_000)
                    show("To send this exact email, reply with /mail_send ${request.token}\n" +
                        "To cancel: /mail_cancel ${request.token}")
                    pending = request.copy(previewOffset = start, presented = true)
                    null
                }
                "/mail_cancel" -> {
                    pending = null
                    show("Email cancelled. Nothing was sent.")
                    null
                }
                else -> {
                    if (!request.presented) {
                        show("Review the email first with /mail_preview ${request.token}. Nothing was sent.")
                        null
                    } else {
                        // Consume before shell work so failures, duplicate updates and retries cannot resend.
                        pending = null
                        request
                    }
                }
            }
        }
        if (toSend != null) {
            val result = try {
                bash.sh(MailAppleScriptCommands.approvalCommand(toSend.reply, send = true),
                    *toSend.args.toTypedArray(), toSend.snapshot)
            } catch (error: ShellException) {
                "Mail send failed: ${error.message}. Request a new preview before trying again."
            }
            show(result)
        }
        return true
    }

    suspend fun clear() = mutex.withLock { pending = null }
}
