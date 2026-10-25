package ru.souz.knowledge

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeStoreCorruptionException
import ru.souz.agent.knowledge.KnowledgeStoreException
import ru.souz.agent.knowledge.KnowledgeStorePersistenceException
import ru.souz.llms.restJsonMapper

/** Versioned retained-content format shared by host-specific Knowledge stores. */
class KnowledgeRecordCodec(private val objectMapper: ObjectMapper = restJsonMapper) {
    fun createEntry(id: String, sourceTool: String, content: String): KnowledgeEntry = KnowledgeEntry(
        id = id,
        sourceTool = sourceTool,
        originalLength = content.length,
        content = retainedContent(content),
    )

    fun serialize(entry: KnowledgeEntry): String {
        val metadata = StoredKnowledgeRecord(
            version = RECORD_VERSION,
            id = entry.id,
            sourceTool = entry.sourceTool,
            originalLength = entry.originalLength,
        )
        val record = when (val content = entry.content) {
            is KnowledgeContent.Complete -> metadata.copy(content = content.content)
            is KnowledgeContent.Truncated -> metadata.copy(head = content.head, tail = content.tail)
        }
        val serialized = objectMapper.writeValueAsString(record)
        if (serialized.toByteArray(StandardCharsets.UTF_8).size > MAX_SERIALIZED_RECORD_BYTES) {
            throw KnowledgeStorePersistenceException(
                "Serialized Knowledge entry exceeds the supported v1 record size."
            )
        }
        return serialized
    }

    fun deserialize(serialized: String, expectedId: String): KnowledgeEntry {
        if (serialized.toByteArray(StandardCharsets.UTF_8).size > MAX_SERIALIZED_RECORD_BYTES) {
            throw KnowledgeStoreCorruptionException("Knowledge entry exceeds the supported v1 record size.")
        }
        val record = try {
            objectMapper.readValue<StoredKnowledgeRecord>(serialized)
        } catch (error: Exception) {
            throw KnowledgeStoreCorruptionException("Knowledge entry contains invalid JSON.", error)
        }
        if (record.version != RECORD_VERSION) {
            throw KnowledgeStoreCorruptionException("Unsupported Knowledge record version: ${record.version}.")
        }
        if (record.id != expectedId) {
            throw KnowledgeStoreCorruptionException("Knowledge entry ID does not match its storage key.")
        }

        val content = when {
            record.content != null && record.head == null && record.tail == null ->
                KnowledgeContent.Complete(record.content)

            record.content == null && record.head != null && record.tail != null ->
                KnowledgeContent.Truncated(head = record.head, tail = record.tail)

            else -> throw KnowledgeStoreCorruptionException(
                "Knowledge entry has an invalid retained-content representation."
            )
        }
        val entry = try {
            KnowledgeEntry(
                id = record.id,
                sourceTool = record.sourceTool,
                originalLength = record.originalLength,
                content = content,
            )
        } catch (error: IllegalArgumentException) {
            throw KnowledgeStoreCorruptionException("Knowledge entry metadata is inconsistent.", error)
        }
        validateV1Shape(entry)
        return entry
    }

    fun canonicalKnowledgeIdOrNull(raw: String): String? {
        val normalized = raw.trim()
        val canonical = runCatching { UUID.fromString(normalized).toString() }.getOrNull() ?: return null
        return canonical.takeIf { normalized.equals(it, ignoreCase = true) }
    }

    inline fun <T> knowledgePersistenceOperation(
        operation: String,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: KnowledgeStoreException) {
        throw error
    } catch (error: Exception) {
        throw KnowledgeStorePersistenceException("Knowledge $operation failed.", error)
    }

    private fun validateV1Shape(entry: KnowledgeEntry) {
        when (val content = entry.content) {
            is KnowledgeContent.Complete -> if (utf8ByteLength(content.content) > MAX_RETAINED_CONTENT_BYTES) {
                throw KnowledgeStoreCorruptionException("Knowledge entry exceeds the retained-content limit.")
            }
            is KnowledgeContent.Truncated -> if (
                utf8ByteLength(content.head) > PART_BYTE_BUDGET ||
                utf8ByteLength(content.tail) > PART_BYTE_BUDGET
            ) {
                throw KnowledgeStoreCorruptionException(
                    "Truncated Knowledge entry exceeds its head or tail retention budget."
                )
            }
        }
    }

    /**
     * Complete results are retained verbatim. Oversized results keep as many whole Unicode code
     * points as fit in independent 512 KiB budgets at the beginning and end; the middle is omitted.
     */
    private fun retainedContent(content: String): KnowledgeContent {
        if (utf8ByteLength(content) <= MAX_RETAINED_CONTENT_BYTES) {
            return KnowledgeContent.Complete(content)
        }

        val headEnd = prefixEndWithinUtf8Budget(content, PART_BYTE_BUDGET)
        val tailStart = suffixStartWithinUtf8Budget(content, PART_BYTE_BUDGET)
        check(headEnd < tailStart) { "Oversized Knowledge content must contain an omitted range." }
        return KnowledgeContent.Truncated(
            head = content.substring(0, headEnd),
            tail = content.substring(tailStart),
        )
    }

    private fun utf8ByteLength(value: String): Long {
        var byteLength = 0L
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            byteLength += codePoint.utf8Width()
            index += Character.charCount(codePoint)
        }
        return byteLength
    }

    private fun prefixEndWithinUtf8Budget(value: String, budget: Long): Int {
        var usedBytes = 0L
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val width = codePoint.utf8Width()
            if (usedBytes + width > budget) break
            usedBytes += width
            index += Character.charCount(codePoint)
        }
        return index
    }

    private fun suffixStartWithinUtf8Budget(value: String, budget: Long): Int {
        var usedBytes = 0L
        var index = value.length
        while (index > 0) {
            val codePoint = value.codePointBefore(index)
            val width = codePoint.utf8Width()
            if (usedBytes + width > budget) break
            usedBytes += width
            index -= Character.charCount(codePoint)
        }
        return index
    }

    private fun Int.utf8Width(): Int = when {
        this <= 0x7f -> 1
        this <= 0x7ff -> 2
        this in 0xd800..0xdfff -> 1
        this <= 0xffff -> 3
        else -> 4
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class StoredKnowledgeRecord(
        val version: Int,
        val id: String,
        val sourceTool: String,
        val originalLength: Int,
        val content: String? = null,
        val head: String? = null,
        val tail: String? = null,
    )

    companion object {
        const val MAX_RETAINED_CONTENT_BYTES: Long = 1_048_576L
        const val PART_BYTE_BUDGET: Long = MAX_RETAINED_CONTENT_BYTES / 2
        const val MAX_SERIALIZED_RECORD_BYTES: Long = 8L * 1_048_576L
        private const val RECORD_VERSION = 1
    }
}
