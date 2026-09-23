package ru.souz.backend.storage.postgres

import com.fasterxml.jackson.databind.JsonNode
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeStoreCorruptionException
import ru.souz.agent.knowledge.KnowledgeStorePersistenceException
import ru.souz.agent.knowledge.KnowledgeStoreUnavailableException
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.backend.app.backendDiModule
import ru.souz.backend.chat.model.Chat
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.knowledge.ToolGetKnowledge
import ru.souz.tool.knowledge.ToolSearchKnowledge

class PostgresConversationKnowledgeStoreTest {
    @Test
    fun `backend wiring round trips complete records without constructing a sandbox`() = knowledgeTest {
        assertIs<PostgresConversationKnowledgeStore>(store)
        val text = "before\u0000\n\t\"\\🙂after"
        val entry = put(text)
        assertEquals(KnowledgeContent.Complete(text), entry.content)
        assertEquals(entry, store.get(meta, " ${entry.id.uppercase()} "))
        assertEquals(text, read(entry)["text"].asText())
        for (invalid in listOf("missing", "../record", "1-1-1-1-1", UUID.randomUUID().toString())) {
            assertNull(store.get(meta, invalid))
            assertEquals("knowledge_not_found", call(getTool, mapOf("knowledgeId" to invalid))["error"]["code"].asText())
        }
    }

    @Test
    fun `truncated records round trip through PostgreSQL and retrieval`() = knowledgeTest {
        val entry = put("head🙂" + "🙂".repeat(300_000) + "🙂tail")
        val content = assertIs<KnowledgeContent.Truncated>(entry.content)
        assertEquals(entry, store.get(meta, entry.id))
        val full = read(entry)
        assertEquals(content.head, full["head"]["text"].asText())
        assertEquals(content.tail, full["tail"]["text"].asText())
    }

    @Test
    fun `ownership scopes writes reads cleanup and database foreign keys`() = knowledgeTest {
        val otherChat = createConversation(meta.userId)
        val otherUser = createConversation("other-user")
        val spacedUser = createConversation(" ${meta.userId} ")
        val own = put("owned")
        val otherEntries = listOf(otherChat, otherUser, spacedUser).associateWith { put("other", it) }
        val foreign = meta.copy(userId = otherUser.userId)
        for (scope in listOf(foreign, otherChat, otherUser, spacedUser)) {
            assertNull(store.get(scope, own.id))
        }
        for (scope in listOf(foreign, meta.copy(conversationId = UUID.randomUUID().toString()))) {
            assertEquals(KnowledgeWriteResult.ConversationUnavailable, store.put(scope, "Tool", "forbidden"))
            store.clearConversation(scope)
            assertEquals(own, store.get(meta, own.id))
        }
        assertFailsWith<java.sql.SQLException> {
            execute("update conversation_knowledge set user_id = ? where id = ?", otherUser.userId, UUID.fromString(own.id))
        }
        store.clearConversation(meta)
        store.clearConversation(meta)
        assertNull(store.get(meta, own.id))
        otherEntries.forEach { (scope, entry) -> assertEquals(entry, store.get(scope, entry.id)) }
    }

    @Test
    fun `missing and noncanonical conversation scope never falls back to user storage`() = knowledgeTest {
        for (id in listOf(null, "", " ", "not-a-chat", " ${meta.conversationId}", "95C3E969-01EF-4F41-9158-A45E643DCB21", "1-1-1-1-1")) {
            val scope = meta.copy(conversationId = id)
            assertEquals(KnowledgeWriteResult.ConversationUnavailable, store.put(scope, "Tool", "content"))
            assertFailsWith<KnowledgeStoreUnavailableException> { store.get(scope, UUID.randomUUID().toString()) }
            assertFailsWith<KnowledgeStoreUnavailableException> { store.clearConversation(scope) }
        }
    }

    @Test
    fun `archive retains references while chat and user deletion cascade`() = knowledgeTest {
        val entry = put("keep when archived")
        PostgresChatRepository(dataSource).updateArchived(meta.userId, UUID.fromString(meta.conversationId), true, Instant.now())
        assertEquals(entry, store.get(meta, entry.id))
        execute("delete from chats where user_id = ? and id = ?", meta.userId, UUID.fromString(meta.conversationId))
        assertNull(store.get(meta, entry.id))
        assertEquals(KnowledgeWriteResult.ConversationUnavailable, store.put(meta, "Tool", "deleted"))
        val next = createConversation(meta.userId)
        val nextEntry = put("delete with user", next)
        execute("delete from users where id = ?", meta.userId)
        assertNull(store.get(next, nextEntry.id))
    }

    @Test
    fun `another instance and replacement writer retrieve the same durable record`() = knowledgeTest {
        val entry = put("durable result")
        PostgresDataSourceFactory.create(config.postgres).use { replica ->
            assertEquals(entry, PostgresConversationKnowledgeStore(replica).get(meta, entry.id))
        }
        dataSource.close()
        PostgresDataSourceFactory.create(config.postgres).use { replacement ->
            val restarted = PostgresConversationKnowledgeStore(replacement)
            assertEquals(entry, restarted.get(meta, entry.id))
            restarted.clearConversation(meta)
            assertNull(restarted.get(meta, entry.id))
        }
    }

    @Test
    fun `collision fails without retrying or overwriting an immutable record`() = knowledgeTest {
        val entry = put("first")
        val id = UUID.fromString(entry.id)
        var attempt = 0
        val colliding = PostgresConversationKnowledgeStore(dataSource) { if (attempt++ == 0) id else UUID.randomUUID() }
        assertFailsWith<KnowledgeStorePersistenceException> { colliding.put(meta, "Tool", "overwrite") }
        assertEquals(1, attempt)
        assertEquals(entry, store.get(meta, entry.id))
    }

    @Test
    fun `corrupt records become storage failures in retrieval tools`() = knowledgeTest {
        val entry = put("valid")
        execute("update conversation_knowledge set record_json = ? where id = ?", "{}", UUID.fromString(entry.id))
        assertFailsWith<KnowledgeStoreCorruptionException> { store.get(meta, entry.id) }
        assertEquals("storage_failure", read(entry)["error"]["code"].asText())
    }

    @Test
    fun `failed commit publishes no entry and database outages remain typed`() = knowledgeTest {
        execute("""create function reject_knowledge() returns trigger language plpgsql as 'begin raise exception ''test commit failure''; end'""")
        execute("""create constraint trigger reject_knowledge after insert on conversation_knowledge deferrable initially deferred for each row execute function reject_knowledge()""")
        val id = UUID.randomUUID()
        val writer = PostgresConversationKnowledgeStore(dataSource) { id }
        assertFailsWith<KnowledgeStorePersistenceException> { writer.put(meta, "Tool", "uncommitted") }
        assertNull(store.get(meta, id.toString()))
        dataSource.close()
        assertFailsWith<KnowledgeStorePersistenceException> { store.put(meta, "Tool", "offline") }
        assertFailsWith<KnowledgeStorePersistenceException> { store.get(meta, id.toString()) }
        assertFailsWith<KnowledgeStorePersistenceException> { store.clearConversation(meta) }
        assertEquals("storage_failure", call(getTool, mapOf("knowledgeId" to id.toString()))["error"]["code"].asText())
        assertEquals("storage_failure", call(searchTool, mapOf("knowledgeId" to id.toString(), "regex" to "x"))["error"]["code"].asText())
    }

    @Test
    fun `cancellation at the connection boundary propagates from every operation`() = knowledgeTest {
        val cancelled = CancellationException("cancel database acquisition")
        val cancelling = PostgresConversationKnowledgeStore(object : DataSource by dataSource {
            override fun getConnection(): java.sql.Connection = throw cancelled
        })
        assertEquals(cancelled.message, assertFailsWith<CancellationException> { cancelling.put(meta, "Tool", "text") }.message)
        assertEquals(cancelled.message, assertFailsWith<CancellationException> { cancelling.get(meta, UUID.randomUUID().toString()) }.message)
        assertEquals(cancelled.message, assertFailsWith<CancellationException> { cancelling.clearConversation(meta) }.message)
    }
}

private fun knowledgeTest(block: suspend KnowledgeFixture.() -> Unit) = runTest {
    KnowledgeFixture().use { fixture ->
        fixture.meta = fixture.createConversation("knowledge-user")
        fixture.block()
    }
}

private class KnowledgeFixture : AutoCloseable {
    val config = postgresAppConfig(newPostgresSchema("knowledge"))
    val dataSource: HikariDataSource = PostgresDataSourceFactory.create(config.postgres)
    val di = DI {
        import(backendDiModule("Knowledge test", config, dataSourceFactory = { dataSource }))
        bindSingleton<ToolInvocationRuntimeSandboxResolver>(overrides = true) { error("Knowledge constructed a sandbox resolver") }
        bindSingleton<RuntimeSandboxFactory>(overrides = true) { error("Knowledge constructed a sandbox factory") }
    }.direct
    val store: ConversationKnowledgeStore = di.instance()
    val getTool: ToolGetKnowledge = di.instance()
    val searchTool: ToolSearchKnowledge = di.instance()
    lateinit var meta: ToolInvocationMeta

    suspend fun createConversation(userId: String): ToolInvocationMeta {
        PostgresUserRepository(dataSource).ensureUser(userId)
        val id = UUID.randomUUID()
        PostgresChatRepository(dataSource).create(Chat(
            id = id, userId = userId, title = null, archived = false,
            clientType = "backend", requestId = id.toString(), payloadHash = "test",
            createdAt = Instant.now(), updatedAt = Instant.now(),
        ))
        return ToolInvocationMeta(userId, id.toString())
    }

    suspend fun put(text: String, scope: ToolInvocationMeta = meta): KnowledgeEntry =
        assertIs<KnowledgeWriteResult.Stored>(store.put(scope, "Tool", text)).entry
    suspend fun read(entry: KnowledgeEntry): JsonNode = call(getTool, mapOf("knowledgeId" to entry.id))
    suspend fun call(tool: LLMToolSetup, arguments: Map<String, Any>): JsonNode =
        restJsonMapper.readTree(tool.invoke(LLMResponse.FunctionCall(tool.fn.name, arguments), meta).content)
    override fun close() = dataSource.close()

    suspend fun execute(sql: String, vararg parameters: Any) = dataSource.write { connection ->
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }
}
