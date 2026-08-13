package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariDataSource
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeStoreCorruptionException
import ru.souz.agent.knowledge.KnowledgeStorePersistenceException
import ru.souz.agent.knowledge.KnowledgeStoreUnavailableException
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.backend.chat.model.Chat
import ru.souz.llms.ToolInvocationMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresConversationKnowledgeStoreTest {
    @Test
    fun `independent store instances round trip complete and truncated content`() = runTest {
        val schema = newPostgresSchema("postgres_conversation_knowledge_restart")
        val userId = "knowledge-user"
        val chatId = UUID.randomUUID()
        val meta = meta(userId, chatId)
        val exactCap = "a".repeat(PostgresConversationKnowledgeStore.MAX_RETAINED_CONTENT_BYTES.toInt())
        val oversized = "a" + "🙂".repeat(262_145) + "b"
        val unusualUtf16 = "before\u0000\uD800after\uDC00"
        val unpairedSurrogateOversized = "\uD800".repeat(
            PostgresConversationKnowledgeStore.MAX_RETAINED_CONTENT_BYTES.toInt() + 1
        )
        lateinit var exactEntry: KnowledgeEntry
        lateinit var emptyEntry: KnowledgeEntry
        lateinit var unusualEntry: KnowledgeEntry
        lateinit var truncatedEntry: KnowledgeEntry
        lateinit var unpairedTruncatedEntry: KnowledgeEntry

        newDataSource(schema).use { firstDataSource ->
            createChat(firstDataSource, userId, chatId)
            val firstStore = PostgresConversationKnowledgeStore(firstDataSource)

            exactEntry = firstStore.put(meta, "ExactTool", exactCap).storedEntry()
            emptyEntry = firstStore.put(meta, "EmptyTool", "").storedEntry()
            unusualEntry = firstStore.put(meta, "Utf16\u0000\uD800Tool", unusualUtf16).storedEntry()
            truncatedEntry = firstStore.put(meta, "EmojiTool", oversized).storedEntry()
            unpairedTruncatedEntry = firstStore.put(
                meta,
                "UnpairedTool",
                unpairedSurrogateOversized,
            ).storedEntry()
        }

        newDataSource(schema).use { secondDataSource ->
            val secondStore = PostgresConversationKnowledgeStore(secondDataSource)

            val storedExact = assertNotNull(secondStore.get(meta, " ${exactEntry.id.uppercase()} "))
            assertEquals(exactEntry, storedExact)
            assertEquals(exactCap, assertIs<KnowledgeContent.Complete>(storedExact.content).content)
            assertEquals(emptyEntry, secondStore.get(meta, emptyEntry.id))
            val storedUnusual = assertNotNull(secondStore.get(meta, unusualEntry.id))
            assertEquals(unusualEntry, storedUnusual)
            assertEquals(
                unusualUtf16.toCharArray().toList(),
                assertIs<KnowledgeContent.Complete>(storedUnusual.content).content.toCharArray().toList(),
            )

            val storedTruncated = assertNotNull(secondStore.get(meta, truncatedEntry.id))
            val truncated = assertIs<KnowledgeContent.Truncated>(storedTruncated.content)
            assertEquals(524_292, storedTruncated.originalLength)
            assertEquals(524_286, storedTruncated.storedLength)
            assertEquals('a', truncated.head.first())
            assertEquals('b', truncated.tail.last())
            listOf(truncated.head, truncated.tail).forEach { part ->
                assertEquals(524_285, part.toByteArray(StandardCharsets.UTF_8).size)
                assertFalse(part.first().isLowSurrogate())
                assertFalse(part.last().isHighSurrogate())
            }

            val storedUnpaired = assertNotNull(secondStore.get(meta, unpairedTruncatedEntry.id))
            val unpaired = assertIs<KnowledgeContent.Truncated>(storedUnpaired.content)
            assertEquals(unpairedSurrogateOversized.length, storedUnpaired.originalLength)
            assertEquals(
                PostgresConversationKnowledgeStore.PART_BYTE_BUDGET.toInt(),
                unpaired.head.length,
            )
            assertEquals(
                PostgresConversationKnowledgeStore.PART_BYTE_BUDGET.toInt(),
                unpaired.tail.length,
            )
            assertTrue(unpaired.head.all(Char::isHighSurrogate))
            assertTrue(unpaired.tail.all(Char::isHighSurrogate))
        }
    }

    @Test
    fun `knowledge and clear are isolated by user and conversation`() = runTest {
        val schema = newPostgresSchema("postgres_conversation_knowledge_isolation")
        val owner = "owner"
        val otherUser = "other-user"
        val ownerChatId = UUID.randomUUID()
        val ownerOtherChatId = UUID.randomUUID()
        val otherChatId = UUID.randomUUID()

        newDataSource(schema).use { dataSource ->
            createChat(dataSource, owner, ownerChatId)
            createChat(dataSource, owner, ownerOtherChatId)
            createChat(dataSource, otherUser, otherChatId)
            val store = PostgresConversationKnowledgeStore(dataSource)
            val ownerMeta = meta(owner, ownerChatId)
            val ownerOtherMeta = meta(owner, ownerOtherChatId)
            val otherMeta = meta(otherUser, otherChatId)
            val ownerEntry = store.put(ownerMeta, "OwnerTool", "owner-private").storedEntry()
            val ownerOtherEntry = store.put(ownerOtherMeta, "OwnerTool", "other-chat").storedEntry()
            val otherEntry = store.put(otherMeta, "OtherTool", "other-user-private").storedEntry()

            assertEquals(ownerEntry, store.get(ownerMeta, ownerEntry.id))
            assertNull(store.get(ownerMeta.copy(userId = otherUser), ownerEntry.id))
            assertNull(store.get(ownerOtherMeta, ownerEntry.id))

            store.clearConversation(ownerMeta.copy(userId = otherUser))
            assertEquals(ownerEntry, store.get(ownerMeta, ownerEntry.id))

            store.clearConversation(ownerMeta)
            store.clearConversation(ownerMeta)

            assertNull(store.get(ownerMeta, ownerEntry.id))
            assertEquals(ownerOtherEntry, store.get(ownerOtherMeta, ownerOtherEntry.id))
            assertEquals(otherEntry, store.get(otherMeta, otherEntry.id))
        }
    }

    @Test
    fun `missing conversation preserves unavailable outcomes`() = runTest {
        val schema = newPostgresSchema("postgres_conversation_knowledge_unavailable")

        newDataSource(schema).use { dataSource ->
            val store = PostgresConversationKnowledgeStore(dataSource)
            val unavailableMetas = listOf(
                ToolInvocationMeta(userId = "user"),
                ToolInvocationMeta(userId = "user", conversationId = " \t "),
            )

            unavailableMetas.forEach { unavailableMeta ->
                assertEquals(
                    KnowledgeWriteResult.ConversationUnavailable,
                    store.put(unavailableMeta, "Tool", "content"),
                )
                assertFailsWith<KnowledgeStoreUnavailableException> {
                    store.get(unavailableMeta, VALID_KNOWLEDGE_ID)
                }
                assertFailsWith<KnowledgeStoreUnavailableException> {
                    store.clearConversation(unavailableMeta)
                }
            }

            val missingMeta = meta("user", UUID.randomUUID())
            assertEquals(
                KnowledgeWriteResult.ConversationUnavailable,
                store.put(missingMeta, "Tool", "content"),
            )
            assertNull(store.get(missingMeta, VALID_KNOWLEDGE_ID))
            store.clearConversation(missingMeta)

            val malformedMeta = ToolInvocationMeta(userId = "user", conversationId = "not-a-uuid")
            assertEquals(
                KnowledgeWriteResult.ConversationUnavailable,
                store.put(malformedMeta, "Tool", "content"),
            )
            assertNull(store.get(malformedMeta, VALID_KNOWLEDGE_ID))
            store.clearConversation(malformedMeta)

            assertFailsWith<IllegalArgumentException> {
                store.put(ToolInvocationMeta(userId = "user"), " ", "content")
            }
        }
    }

    @Test
    fun `chat deletion cascades to knowledge`() = runTest {
        val schema = newPostgresSchema("postgres_conversation_knowledge_cascade")
        val userId = "cascade-user"
        val chatId = UUID.randomUUID()
        val meta = meta(userId, chatId)

        newDataSource(schema).use { dataSource ->
            createChat(dataSource, userId, chatId)
            val store = PostgresConversationKnowledgeStore(dataSource)
            val entry = store.put(meta, "Tool", "temporary").storedEntry()

            dataSource.write { connection ->
                connection.prepareStatement(
                    "delete from chats where user_id = ? and id = ?"
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.setObject(2, chatId)
                    assertEquals(1, statement.executeUpdate())
                }
            }

            assertNull(store.get(meta, entry.id))
            assertEquals(0, knowledgeCount(dataSource))
        }
    }

    @Test
    fun `user deletion cascades all tenant knowledge without touching another tenant`() = runTest {
        val schema = newPostgresSchema("postgres_conversation_knowledge_user_cascade")
        val deletedUser = "deleted-user"
        val retainedUser = "retained-user"
        val deletedChatId = UUID.randomUUID()
        val retainedChatId = UUID.randomUUID()

        newDataSource(schema).use { dataSource ->
            createChat(dataSource, deletedUser, deletedChatId)
            createChat(dataSource, retainedUser, retainedChatId)
            val store = PostgresConversationKnowledgeStore(dataSource)
            val deletedEntry = store.put(meta(deletedUser, deletedChatId), "Tool", "delete me").storedEntry()
            val retainedEntry = store.put(meta(retainedUser, retainedChatId), "Tool", "keep me").storedEntry()

            dataSource.write { connection ->
                connection.prepareStatement("delete from users where id = ?").use { statement ->
                    statement.setString(1, deletedUser)
                    assertEquals(1, statement.executeUpdate())
                }
            }

            assertNull(store.get(meta(deletedUser, deletedChatId), deletedEntry.id))
            assertEquals(retainedEntry, store.get(meta(retainedUser, retainedChatId), retainedEntry.id))
            assertEquals(1, knowledgeCount(dataSource))
        }
    }

    @Test
    fun `repeated generated ID fails explicitly without replacing existing knowledge`() = runTest {
        val schema = newPostgresSchema("postgres_conversation_knowledge_id_collision")
        val userId = "collision-user"
        val chatId = UUID.randomUUID()
        val repeatedId = UUID.randomUUID()
        val conversationMeta = meta(userId, chatId)

        newDataSource(schema).use { dataSource ->
            createChat(dataSource, userId, chatId)
            val store = PostgresConversationKnowledgeStore(dataSource) { repeatedId }
            val original = store.put(conversationMeta, "OriginalTool", "original").storedEntry()

            assertFailsWith<KnowledgeStorePersistenceException> {
                store.put(conversationMeta, "ReplacementTool", "replacement")
            }

            assertEquals(original, store.get(conversationMeta, original.id))
            assertEquals(1, knowledgeCount(dataSource))
        }
    }

    @Test
    fun `inconsistent rows and database failures keep domain exceptions`() = runTest {
        val schema = newPostgresSchema("postgres_conversation_knowledge_errors")
        val userId = "error-user"
        val chatId = UUID.randomUUID()
        val meta = meta(userId, chatId)
        val dataSource = newDataSource(schema)
        createChat(dataSource, userId, chatId)
        val store = PostgresConversationKnowledgeStore(dataSource)
        val entry = store.put(meta, "Tool", "content").storedEntry()

        dataSource.write { connection ->
            connection.prepareStatement(
                "alter table conversation_knowledge drop constraint conversation_knowledge_content_shape"
            ).use { statement ->
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                update conversation_knowledge
                set original_length = original_length + 1
                where user_id = ? and chat_id = ? and id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.setObject(2, chatId)
                statement.setObject(3, UUID.fromString(entry.id))
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertFailsWith<KnowledgeStoreCorruptionException> { store.get(meta, entry.id) }

        dataSource.close()

        assertNull(store.get(meta, "not-a-uuid"))
        val error = assertFailsWith<KnowledgeStorePersistenceException> {
            store.get(meta, entry.id)
        }
        assertTrue(error.cause != null)
    }

    private fun newDataSource(schema: String): HikariDataSource =
        PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

    private suspend fun createChat(
        dataSource: HikariDataSource,
        userId: String,
        chatId: UUID,
    ) {
        PostgresUserRepository(dataSource).ensureUser(userId)
        PostgresChatRepository(dataSource).create(
            Chat(
                id = chatId,
                userId = userId,
                title = null,
                archived = false,
                createdAt = FIXED_TIME,
                updatedAt = FIXED_TIME,
            )
        )
    }

    private fun meta(userId: String, chatId: UUID): ToolInvocationMeta =
        ToolInvocationMeta(userId = userId, conversationId = chatId.toString())

    private fun knowledgeCount(dataSource: HikariDataSource): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("select count(*) from conversation_knowledge").use { statement ->
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                }
            }
        }

    private fun KnowledgeWriteResult.storedEntry(): KnowledgeEntry =
        assertIs<KnowledgeWriteResult.Stored>(this).entry

    private companion object {
        val FIXED_TIME: Instant = Instant.parse("2026-08-12T10:00:00Z")
        const val VALID_KNOWLEDGE_ID: String = "123e4567-e89b-12d3-a456-426614174000"
    }
}
