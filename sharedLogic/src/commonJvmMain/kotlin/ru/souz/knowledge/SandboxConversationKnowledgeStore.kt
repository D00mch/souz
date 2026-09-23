package ru.souz.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeStoreCorruptionException
import ru.souz.agent.knowledge.KnowledgeStorePersistenceException
import ru.souz.agent.knowledge.KnowledgeStoreUnavailableException
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.knowledge.KnowledgeRecordCodec.Companion.MAX_SERIALIZED_RECORD_BYTES
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver

class SandboxConversationKnowledgeStore(
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
    objectMapper: ObjectMapper = restJsonMapper,
    private val idGenerator: () -> UUID = UUID::randomUUID,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConversationKnowledgeStore {
    private val codec = KnowledgeRecordCodec(objectMapper)
    private val logger = LoggerFactory.getLogger(SandboxConversationKnowledgeStore::class.java)

    override suspend fun put(
        meta: ToolInvocationMeta,
        sourceTool: String,
        content: String,
    ): KnowledgeWriteResult {
        require(sourceTool.isNotBlank()) { "Knowledge source tool must not be blank." }
        val conversationId = availableConversationId(meta)
            ?: return KnowledgeWriteResult.ConversationUnavailable

        return withContext(ioDispatcher) {
            codec.knowledgePersistenceOperation("write") {
                val sandbox = sandboxResolver.resolve(meta)
                val fileSystem = sandbox.fileSystem
                val conversationDirectory = conversationDirectory(sandbox, meta.userId, conversationId)

                repeat(MAX_ID_GENERATION_ATTEMPTS) {
                    val id = idGenerator().toString()
                    val recordPath = fileSystem.resolvePath(recordPath(conversationDirectory, id))
                    if (recordPath.exists) {
                        return@repeat
                    }

                    val knowledgeEntry = codec.createEntry(
                        id = id,
                        sourceTool = sourceTool,
                        content = content,
                    )
                    val serialized = codec.serialize(knowledgeEntry)
                    fileSystem.writeTextAtomically(
                        path = recordPath,
                        content = serialized,
                        logger = logger,
                    )
                    return@knowledgePersistenceOperation KnowledgeWriteResult.Stored(knowledgeEntry)
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
        val canonicalId = codec.canonicalKnowledgeIdOrNull(knowledgeId) ?: return null

        return withContext(ioDispatcher) {
            codec.knowledgePersistenceOperation("read") {
                val sandbox = sandboxResolver.resolve(meta)
                val fileSystem = sandbox.fileSystem
                val path = fileSystem.resolvePath(
                    recordPath(
                        conversationDirectory(sandbox, meta.userId, conversationId),
                        canonicalId,
                    )
                )
                if (!path.exists) {
                    return@knowledgePersistenceOperation null
                }
                readEntry(fileSystem, path, canonicalId)
            }
        }
    }

    override suspend fun clearConversation(meta: ToolInvocationMeta) {
        val conversationId = requireConversationId(meta)
        withContext(ioDispatcher) {
            codec.knowledgePersistenceOperation("clear") {
                val sandbox = sandboxResolver.resolve(meta)
                val fileSystem = sandbox.fileSystem
                val directory = fileSystem.resolvePath(
                    conversationDirectory(sandbox, meta.userId, conversationId)
                )
                if (!directory.exists) {
                    return@knowledgePersistenceOperation
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

    private fun readEntry(
        fileSystem: SandboxFileSystem,
        path: SandboxPathInfo,
        expectedId: String,
    ): KnowledgeEntry {
        if (!path.isRegularFile || path.isSymbolicLink) {
            throw KnowledgeStoreCorruptionException("Knowledge entry is not a regular file.")
        }
        if ((path.sizeBytes ?: 0) > MAX_SERIALIZED_RECORD_BYTES) {
            throw KnowledgeStoreCorruptionException("Knowledge entry exceeds the supported v1 record size.")
        }
        return codec.deserialize(fileSystem.readText(path), expectedId)
    }

    private fun conversationDirectory(
        sandbox: RuntimeSandbox,
        userId: String,
        conversationId: String,
    ): String = sandboxPath(
        sandbox.runtimePaths.stateRootPath,
        KNOWLEDGE_DIRECTORY,
        USERS_DIRECTORY,
        scopeKey(userId),
        CONVERSATIONS_DIRECTORY,
        scopeKey(conversationId),
    )

    private fun recordPath(conversationDirectory: String, id: String): String =
        sandboxPath(conversationDirectory, "$id.json")

    /** Keeps container paths POSIX-shaped instead of interpreting them through the host filesystem. */
    private fun sandboxPath(root: String, vararg segments: String): String = buildString {
        append(root.trimEnd('/'))
        segments.forEach { segment ->
            append('/')
            append(segment)
        }
    }

    private fun scopeKey(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun availableConversationId(meta: ToolInvocationMeta): String? =
        meta.conversationId?.takeIf(String::isNotBlank)

    private fun requireConversationId(meta: ToolInvocationMeta): String =
        availableConversationId(meta)
            ?: throw KnowledgeStoreUnavailableException(
                "Knowledge storage requires a nonblank conversation ID."
            )

    internal companion object {
        private const val MAX_ID_GENERATION_ATTEMPTS = 16
        private const val KNOWLEDGE_DIRECTORY = "knowledge"
        private const val USERS_DIRECTORY = "users"
        private const val CONVERSATIONS_DIRECTORY = "conversations"
    }
}
