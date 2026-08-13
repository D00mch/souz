package ru.souz.backend.storage.postgres

import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeStoreCorruptionException
import ru.souz.agent.knowledge.KnowledgeStoreException
import ru.souz.agent.knowledge.KnowledgeStorePersistenceException
import ru.souz.agent.knowledge.KnowledgeStoreUnavailableException
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.llms.ToolInvocationMeta

class PostgresConversationKnowledgeStore(
    private val dataSource: DataSource,
    private val idGenerator: () -> UUID = UUID::randomUUID,
) : ConversationKnowledgeStore {
    override suspend fun put(
        meta: ToolInvocationMeta,
        sourceTool: String,
        content: String,
    ): KnowledgeWriteResult {
        require(sourceTool.isNotBlank()) { "Knowledge source tool must not be blank." }
        val conversationId = availableConversationUuid(meta)
            ?: return KnowledgeWriteResult.ConversationUnavailable
        val retainedContent = retainedContent(content)

        return runPersistenceOperation("write") {
            dataSource.write { connection ->
                repeat(MAX_ID_GENERATION_ATTEMPTS) {
                    val id = idGenerator()
                    val entry = KnowledgeEntry(
                        id = id.toString(),
                        sourceTool = sourceTool,
                        originalLength = content.length,
                        content = retainedContent,
                    )
                    val inserted = connection.prepareStatement(
                        """
                        insert into conversation_knowledge(
                          user_id, chat_id, id, source_tool, original_length,
                          complete_content, head_content, tail_content
                        )
                        select ?, ?, ?, ?, ?, ?, ?, ?
                        from chats
                        where user_id = ? and id = ?
                        on conflict (user_id, chat_id, id) do nothing
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, meta.userId)
                        statement.setObject(2, conversationId)
                        statement.setObject(3, id)
                        statement.setBytes(4, entry.sourceTool.toUtf16Bytes())
                        statement.setInt(5, entry.originalLength)
                        when (val entryContent = entry.content) {
                            is KnowledgeContent.Complete -> {
                                statement.setBytes(6, entryContent.content.toUtf16Bytes())
                                statement.setBytes(7, null)
                                statement.setBytes(8, null)
                            }

                            is KnowledgeContent.Truncated -> {
                                statement.setBytes(6, null)
                                statement.setBytes(7, entryContent.head.toUtf16Bytes())
                                statement.setBytes(8, entryContent.tail.toUtf16Bytes())
                            }
                        }
                        statement.setString(9, meta.userId)
                        statement.setObject(10, conversationId)
                        statement.executeUpdate() == 1
                    }
                    if (inserted) {
                        return@write KnowledgeWriteResult.Stored(entry)
                    }

                    val conversationExists = connection.prepareStatement(
                        "select exists(select 1 from chats where user_id = ? and id = ?)"
                    ).use { statement ->
                        statement.setString(1, meta.userId)
                        statement.setObject(2, conversationId)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next()
                            resultSet.getBoolean(1)
                        }
                    }
                    if (!conversationExists) {
                        return@write KnowledgeWriteResult.ConversationUnavailable
                    }
                }

                throw KnowledgeStorePersistenceException(
                    "Failed to allocate a unique Knowledge ID after $MAX_ID_GENERATION_ATTEMPTS attempts."
                )
            }
        }
    }

    override suspend fun get(
        meta: ToolInvocationMeta,
        knowledgeId: String,
    ): KnowledgeEntry? {
        val conversationId = conversationUuidOrNull(meta) ?: return null
        val canonicalKnowledgeId = canonicalUuidOrNull(knowledgeId) ?: return null

        return runPersistenceOperation("read") {
            dataSource.read { connection ->
                connection.prepareStatement(
                    """
                    select id, source_tool, original_length,
                           complete_content, head_content, tail_content
                    from conversation_knowledge
                    where user_id = ? and chat_id = ? and id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, meta.userId)
                    statement.setObject(2, conversationId)
                    statement.setObject(3, canonicalKnowledgeId)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) resultSet.toKnowledgeEntry() else null
                    }
                }
            }
        }
    }

    override suspend fun clearConversation(meta: ToolInvocationMeta) {
        val conversationId = conversationUuidOrNull(meta) ?: return

        runPersistenceOperation("clear") {
            dataSource.write { connection ->
                connection.prepareStatement(
                    "delete from conversation_knowledge where user_id = ? and chat_id = ?"
                ).use { statement ->
                    statement.setString(1, meta.userId)
                    statement.setObject(2, conversationId)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun ResultSet.toKnowledgeEntry(): KnowledgeEntry {
        val completeContent = getBytes("complete_content")?.toUtf16String()
        val headContent = getBytes("head_content")?.toUtf16String()
        val tailContent = getBytes("tail_content")?.toUtf16String()
        val content = when {
            completeContent != null && headContent == null && tailContent == null ->
                KnowledgeContent.Complete(completeContent)

            completeContent == null && headContent != null && tailContent != null ->
                KnowledgeContent.Truncated(head = headContent, tail = tailContent)

            else -> throw KnowledgeStoreCorruptionException(
                "Knowledge entry has an invalid retained-content representation."
            )
        }
        val entry = try {
            KnowledgeEntry(
                id = getObject("id", UUID::class.java).toString(),
                sourceTool = getBytes("source_tool").toUtf16String(),
                originalLength = getInt("original_length"),
                content = content,
            )
        } catch (error: IllegalArgumentException) {
            throw KnowledgeStoreCorruptionException("Knowledge entry metadata is inconsistent.", error)
        }
        validateRetainedContent(entry)
        return entry
    }

    private fun validateRetainedContent(entry: KnowledgeEntry) {
        val retainedBytes = when (val content = entry.content) {
            is KnowledgeContent.Complete -> utf8ByteLength(content.content)
            is KnowledgeContent.Truncated -> utf8ByteLength(content.head) + utf8ByteLength(content.tail)
        }
        if (retainedBytes > MAX_RETAINED_CONTENT_BYTES) {
            throw KnowledgeStoreCorruptionException("Knowledge entry exceeds the retained-content limit.")
        }

        val truncated = entry.content as? KnowledgeContent.Truncated ?: return
        if (
            utf8ByteLength(truncated.head) > PART_BYTE_BUDGET ||
            utf8ByteLength(truncated.tail) > PART_BYTE_BUDGET
        ) {
            throw KnowledgeStoreCorruptionException(
                "Truncated Knowledge entry exceeds its head or tail retention budget."
            )
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

    private fun availableConversationUuid(meta: ToolInvocationMeta): UUID? {
        val conversationId = meta.conversationId?.takeIf(String::isNotBlank) ?: return null
        return exactUuidOrNull(conversationId)
    }

    private fun conversationUuidOrNull(meta: ToolInvocationMeta): UUID? {
        val conversationId = meta.conversationId?.takeIf(String::isNotBlank)
            ?: throw KnowledgeStoreUnavailableException(
                "Knowledge storage requires a nonblank conversation ID."
            )
        return exactUuidOrNull(conversationId)
    }

    private fun exactUuidOrNull(raw: String): UUID? {
        val parsed = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        return parsed.takeIf { raw.equals(it.toString(), ignoreCase = true) }
    }

    private fun canonicalUuidOrNull(raw: String): UUID? {
        val normalized = raw.trim()
        val parsed = runCatching { UUID.fromString(normalized) }.getOrNull() ?: return null
        return parsed.takeIf { normalized.equals(it.toString(), ignoreCase = true) }
    }

    private suspend fun <T> runPersistenceOperation(
        operation: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: KnowledgeStoreException) {
        throw error
    } catch (error: Exception) {
        throw KnowledgeStorePersistenceException("Knowledge $operation failed.", error)
    }

    internal companion object {
        const val MAX_RETAINED_CONTENT_BYTES: Long = 1_048_576L
        const val PART_BYTE_BUDGET: Long = MAX_RETAINED_CONTENT_BYTES / 2

        private const val MAX_ID_GENERATION_ATTEMPTS = 16
    }
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

private fun String.toUtf16Bytes(): ByteArray {
    val bytes = ByteArray(length * 2)
    forEachIndexed { index, codeUnit ->
        val value = codeUnit.code
        bytes[index * 2] = (value ushr 8).toByte()
        bytes[index * 2 + 1] = value.toByte()
    }
    return bytes
}

private fun ByteArray.toUtf16String(): String {
    if (size % 2 != 0) {
        throw KnowledgeStoreCorruptionException("Knowledge content has an invalid UTF-16 representation.")
    }
    val codeUnits = CharArray(size / 2) { index ->
        val high = this[index * 2].toInt() and 0xff
        val low = this[index * 2 + 1].toInt() and 0xff
        ((high shl 8) or low).toChar()
    }
    return codeUnits.concatToString()
}
