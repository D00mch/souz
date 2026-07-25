package ru.souz.knowledge

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeStore
import ru.souz.agent.knowledge.KnowledgeStoreCorruptionException
import ru.souz.agent.knowledge.KnowledgeStoreException
import ru.souz.agent.knowledge.KnowledgeStorePersistenceException
import ru.souz.agent.knowledge.KnowledgeStoreUnavailableException
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver

class SandboxKnowledgeStore(
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
    private val objectMapper: ObjectMapper = restJsonMapper,
    private val idGenerator: () -> UUID = UUID::randomUUID,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : KnowledgeStore {
    private val logger = LoggerFactory.getLogger(SandboxKnowledgeStore::class.java)

    override suspend fun put(
        meta: ToolInvocationMeta,
        sourceTool: String,
        content: String,
    ): KnowledgeWriteResult {
        require(sourceTool.isNotBlank()) { "Knowledge source tool must not be blank." }
        val conversationId = normalizedConversationId(meta)
            ?: return KnowledgeWriteResult.ConversationUnavailable

        return withContext(ioDispatcher) {
            runPersistenceOperation("write") {
                val sandbox = sandboxResolver.resolve(meta)
                val fileSystem = sandbox.fileSystem
                val conversationDirectory = conversationDirectory(sandbox, meta.userId, conversationId)
                val contentToStore = retainedContent(content)

                repeat(MAX_ID_GENERATION_ATTEMPTS) {
                    val id = idGenerator().toString()
                    val recordPath = fileSystem.resolvePath(recordPath(conversationDirectory, id).toString())
                    if (recordPath.exists) {
                        return@repeat
                    }

                    val knowledgeEntry = KnowledgeEntry(
                        id = id,
                        sourceTool = sourceTool,
                        originalLength = content.length,
                        content = contentToStore,
                    )
                    val serialized = serialize(knowledgeEntry)
                    fileSystem.writeTextAtomically(
                        path = recordPath,
                        content = serialized,
                        logger = logger,
                    )
                    return@runPersistenceOperation KnowledgeWriteResult.Stored(knowledgeEntry)
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
        val conversationId = requireConversationId(meta)
        val canonicalId = canonicalKnowledgeIdOrNull(knowledgeId) ?: return null

        return withContext(ioDispatcher) {
            runPersistenceOperation("read") {
                val sandbox = sandboxResolver.resolve(meta)
                val fileSystem = sandbox.fileSystem
                val path = fileSystem.resolvePath(
                    recordPath(
                        conversationDirectory(sandbox, meta.userId, conversationId),
                        canonicalId,
                    ).toString()
                )
                if (!path.exists) {
                    return@runPersistenceOperation null
                }
                readEntry(fileSystem, path, canonicalId)
            }
        }
    }

    override suspend fun clearConversation(meta: ToolInvocationMeta) {
        val conversationId = requireConversationId(meta)
        withContext(ioDispatcher) {
            runPersistenceOperation("clear") {
                val sandbox = sandboxResolver.resolve(meta)
                val fileSystem = sandbox.fileSystem
                val directory = fileSystem.resolvePath(
                    conversationDirectory(sandbox, meta.userId, conversationId).toString()
                )
                if (!directory.exists) {
                    return@runPersistenceOperation
                }
                if (!directory.isDirectory || directory.isSymbolicLink) {
                    throw KnowledgeStoreCorruptionException(
                        "Knowledge conversation storage is not a regular directory."
                    )
                }
                fileSystem.delete(directory, recursively = true)
            }
        }
    }

    private fun serialize(entry: KnowledgeEntry): String {
        val record = when (val content = entry.content) {
            is KnowledgeContent.Complete -> StoredKnowledgeRecord(
                version = RECORD_VERSION,
                id = entry.id,
                sourceTool = entry.sourceTool,
                originalLength = entry.originalLength,
                content = content.content,
            )

            is KnowledgeContent.Truncated -> StoredKnowledgeRecord(
                version = RECORD_VERSION,
                id = entry.id,
                sourceTool = entry.sourceTool,
                originalLength = entry.originalLength,
                head = content.head,
                tail = content.tail,
            )
        }
        val serialized = objectMapper.writeValueAsString(record)
        if (serialized.toByteArray(StandardCharsets.UTF_8).size > MAX_SERIALIZED_RECORD_BYTES) {
            throw KnowledgeStorePersistenceException(
                "Serialized Knowledge entry exceeds the supported v1 record size."
            )
        }
        return serialized
    }

    private fun readEntry(
        fileSystem: SandboxFileSystem,
        path: SandboxPathInfo,
        expectedId: String,
    ): KnowledgeEntry {
        if (!path.isRegularFile || path.isSymbolicLink) {
            throw KnowledgeStoreCorruptionException("Knowledge entry is not a regular file.")
        }
        path.sizeBytes?.let { sizeBytes ->
            if (sizeBytes > MAX_SERIALIZED_RECORD_BYTES) {
                throw KnowledgeStoreCorruptionException("Knowledge entry exceeds the supported v1 record size.")
            }
        }

        val serialized = fileSystem.readText(path)
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

    private fun validateV1Shape(entry: KnowledgeEntry) {
        val storedBytes = when (val content = entry.content) {
            is KnowledgeContent.Complete -> utf8ByteLength(content.content)
            is KnowledgeContent.Truncated -> utf8ByteLength(content.head) + utf8ByteLength(content.tail)
        }
        if (storedBytes > MAX_RETAINED_CONTENT_BYTES) {
            throw KnowledgeStoreCorruptionException("Knowledge entry exceeds the retained-content limit.")
        }

        when (val content = entry.content) {
            is KnowledgeContent.Complete -> Unit
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

    private fun conversationDirectory(
        sandbox: RuntimeSandbox,
        userId: String,
        conversationId: String,
    ): Path = Path.of(sandbox.runtimePaths.stateRootPath)
        .resolve(KNOWLEDGE_DIRECTORY)
        .resolve(USERS_DIRECTORY)
        .resolve(scopeKey(userId.trim()))
        .resolve(CONVERSATIONS_DIRECTORY)
        .resolve(scopeKey(conversationId))

    private fun recordPath(conversationDirectory: Path, id: String): Path =
        conversationDirectory.resolve("$id.json")

    private fun scopeKey(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun normalizedConversationId(meta: ToolInvocationMeta): String? =
        meta.conversationId?.trim()?.takeIf(String::isNotEmpty)

    private fun requireConversationId(meta: ToolInvocationMeta): String =
        normalizedConversationId(meta)
            ?: throw KnowledgeStoreUnavailableException(
                "Knowledge storage requires a nonblank conversation ID."
            )

    private fun canonicalKnowledgeIdOrNull(raw: String): String? {
        val normalized = raw.trim()
        val canonical = runCatching { UUID.fromString(normalized).toString() }.getOrNull() ?: return null
        return canonical.takeIf { normalized.equals(it, ignoreCase = true) }
    }

    private inline fun <T> runPersistenceOperation(
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

    internal companion object {
        const val MAX_RETAINED_CONTENT_BYTES: Long = 1_048_576L
        const val PART_BYTE_BUDGET: Long = MAX_RETAINED_CONTENT_BYTES / 2
        const val MAX_SERIALIZED_RECORD_BYTES: Long = 8L * 1_048_576L

        private const val RECORD_VERSION = 1
        private const val MAX_ID_GENERATION_ATTEMPTS = 16
        private const val KNOWLEDGE_DIRECTORY = "knowledge"
        private const val USERS_DIRECTORY = "users"
        private const val CONVERSATIONS_DIRECTORY = "conversations"
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
