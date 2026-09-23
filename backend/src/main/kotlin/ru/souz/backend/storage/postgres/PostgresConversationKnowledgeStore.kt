package ru.souz.backend.storage.postgres

import java.util.UUID
import javax.sql.DataSource
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeStoreUnavailableException
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.knowledge.KnowledgeRecordCodec
import ru.souz.llms.ToolInvocationMeta

class PostgresConversationKnowledgeStore(
    private val dataSource: DataSource,
    private val idGenerator: () -> UUID = UUID::randomUUID,
) : ConversationKnowledgeStore {
    private val codec = KnowledgeRecordCodec()

    override suspend fun put(meta: ToolInvocationMeta, sourceTool: String, content: String): KnowledgeWriteResult {
        require(sourceTool.isNotBlank()) { "Knowledge source tool must not be blank." }
        val chatId = conversationIdOrNull(meta) ?: return KnowledgeWriteResult.ConversationUnavailable
        return codec.knowledgePersistenceOperation("write") {
            dataSource.write { connection ->
                // Keep ownership stable until the insert commits; deletion then cascades.
                val owned = connection.prepareStatement(
                    "select id from chats where user_id = ? and id = ? for key share"
                ).use { statement ->
                    statement.setString(1, meta.userId)
                    statement.setObject(2, chatId)
                    statement.executeQuery().use { it.next() }
                }
                if (!owned) return@write KnowledgeWriteResult.ConversationUnavailable

                val id = idGenerator()
                val entry = codec.createEntry(id.toString(), sourceTool, content)
                connection.prepareStatement(
                    """
                    insert into conversation_knowledge(id, user_id, chat_id, record_json)
                    values (?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setString(2, meta.userId)
                    statement.setObject(3, chatId)
                    statement.setString(4, codec.serialize(entry))
                    statement.executeUpdate()
                }
                KnowledgeWriteResult.Stored(entry)
            }
        }
    }

    override suspend fun get(meta: ToolInvocationMeta, knowledgeId: String): KnowledgeEntry? {
        val chatId = requireConversationId(meta)
        val id = codec.canonicalKnowledgeIdOrNull(knowledgeId) ?: return null
        return codec.knowledgePersistenceOperation("read") {
            dataSource.read { connection ->
                connection.prepareStatement(
                    "select record_json from conversation_knowledge where user_id = ? and chat_id = ? and id = ?"
                ).use { statement ->
                    statement.setString(1, meta.userId)
                    statement.setObject(2, chatId)
                    statement.setObject(3, UUID.fromString(id))
                    statement.executeQuery().use { result ->
                        if (result.next()) codec.deserialize(result.getString("record_json"), id) else null
                    }
                }
            }
        }
    }

    override suspend fun clearConversation(meta: ToolInvocationMeta) {
        val chatId = requireConversationId(meta)
        codec.knowledgePersistenceOperation("clear") {
            dataSource.write { connection ->
                connection.prepareStatement(
                    "delete from conversation_knowledge where user_id = ? and chat_id = ?"
                ).use { statement ->
                    statement.setString(1, meta.userId)
                    statement.setObject(2, chatId)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun conversationIdOrNull(meta: ToolInvocationMeta): UUID? =
        meta.conversationId?.let { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString() == raw }
        }

    private fun requireConversationId(meta: ToolInvocationMeta): UUID =
        conversationIdOrNull(meta)
            ?: throw KnowledgeStoreUnavailableException("Knowledge storage requires a canonical chat UUID.")
}
