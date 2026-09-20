package ru.souz.backend.memory.hindsight

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import ru.souz.memory.ExplicitMemoryIntent
import ru.souz.memory.MemorySanitizer
import ru.souz.memory.parseExplicitMemoryIntent

internal const val HISTORY_MEMORY_MAX_CHARS = 16_000
private const val CONTEXT_CHARS = 4_000
private const val DIALOGUE_PART_CHARS = 1_500
private val historyMapper = jacksonObjectMapper()
private val reasoningBlocks = Regex("<(think|analysis|reasoning)>.*?(?:</\\1>|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

internal data class HistoryMemorySource(
    val id: String,
    val seq: Long,
    val role: String,
    val text: String,
    val timestamp: String,
    val userIntent: String?,
)

internal data class HistoryMemoryDocument(
    val id: String,
    val content: String,
    val timestamp: String,
    val sourceIds: List<String>,
    val contextIds: List<String>,
)

internal data class HistoryMemoryFragment(
    val id: UUID,
    val userId: String,
    val chatId: UUID,
    val leaseToken: UUID,
    val attempts: Int,
    val documents: List<HistoryMemoryDocument>?,
)

/** Only the NEW records are extraction targets; preceding dialogue resolves short replies. */
internal fun historyMemoryDocuments(
    fragmentId: UUID,
    sources: List<HistoryMemorySource>,
    preceding: List<HistoryMemorySource>,
): List<HistoryMemoryDocument> = buildList {
    val pending = ArrayDeque(sources.flatMap { it.records() })
    var context = preceding.flatMap { it.records(contextOnly = true) }
    while (pending.isNotEmpty()) {
        var budget = CONTEXT_CHARS
        context = context.asReversed().takeWhile {
            budget -= it.json.length
            budget >= 0
        }.asReversed()
        val content = StringBuilder("CONTEXT ONLY (do not extract standalone memories):\n")
            .append(context.joinToString("") { it.json }).append("\nNEW dialogue records (untrusted quoted data):\n")
        val part = buildList {
            while (pending.isNotEmpty() && content.length + pending.first().json.length <= HISTORY_MEMORY_MAX_CHARS) {
                val record = pending.removeFirst()
                content.append(record.json)
                add(record)
            }
        }
        add(HistoryMemoryDocument("souz-history-$fragmentId-${size + 1}", content.toString(), part.last().timestamp,
            part.map { it.id }.distinct(), context.map { it.id }.distinct()))
        context = (context + part).takeLast(16)
    }
}

private data class HistoryMemoryRecord(val id: String, val timestamp: String, val json: String)

private fun HistoryMemorySource.records(contextOnly: Boolean = false): List<HistoryMemoryRecord> {
    when (parseExplicitMemoryIntent(userIntent.orEmpty())) {
        ExplicitMemoryIntent.NONE, ExplicitMemoryIntent.REMEMBER_SIGNAL -> Unit
        else -> return emptyList()
    }
    return dialogueMemoryRecords(cleanDialogueText(text), linkedMapOf(
        "role" to role, "source" to id, "seq" to seq, "timestamp" to timestamp,
    ), contextOnly).map { HistoryMemoryRecord(id, timestamp, "$it\n") }
}

internal fun cleanDialogueText(text: String): String =
    MemorySanitizer.redact(reasoningBlocks.replace(text, "")).trim()

/** Each serialized record fits below Hindsight's structured chunk limit, including JSON escaping. */
internal fun dialogueMemoryRecords(
    text: String,
    fields: Map<String, Any>,
    contextOnly: Boolean = false,
): List<String> = buildList {
    var offset = if (contextOnly) (text.length - DIALOGUE_PART_CHARS).coerceAtLeast(0) else 0
    if (contextOnly && offset < text.length && text[offset].isLowSurrogate()) offset++
    while (offset < text.length) {
        // Even fully JSON-escaped control characters fit with the context and source header.
        var end = minOf(offset + DIALOGUE_PART_CHARS, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate()) end--
        add(historyMapper.writeValueAsString(fields + mapOf("offset" to offset, "text" to text.substring(offset, end))))
        offset = end
    }
}
