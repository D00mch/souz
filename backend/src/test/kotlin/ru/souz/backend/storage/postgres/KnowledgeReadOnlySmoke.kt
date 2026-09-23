package ru.souz.backend.storage.postgres

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.backend.app.BackendAppConfig
import ru.souz.backend.app.BackendPostgresConfig
import ru.souz.backend.app.BackendServerConfig
import ru.souz.backend.app.backendDiModule
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.knowledge.ToolGetKnowledge
import ru.souz.tool.knowledge.ToolSearchKnowledge

/** Runs in two disposable read-only JVM containers via tools/knowledge-readonly-smoke.sh. */
fun main(args: Array<String>) = runBlocking {
    check(args.size == 2 && args[0] in setOf("write", "read-clear"))
    check(!Files.isWritable(Path.of("/app")) && !Files.isWritable(Path.of("/tmp")))
    check(!Files.exists(Path.of("/var/run/docker.sock")))
    val config = BackendAppConfig(
        featureFlags = BackendFeatureFlags(),
        server = BackendServerConfig(host = "0.0.0.0", port = 8080, proxyToken = null),
        postgres = BackendPostgresConfig(
            host = "postgres", port = 5432, database = "souz", user = "souz", password = "souz",
            schema = "public", maxPoolSize = 2, connectionTimeoutMs = 10_000, dsn = args[1],
        ),
    )
    PostgresDataSourceFactory.create(config.postgres).use { dataSource ->
        val di = DI {
            import(backendDiModule("read-only Knowledge smoke", config, dataSourceFactory = { dataSource }))
            bindSingleton<RuntimeSandboxFactory>(overrides = true) { error("Sandbox factory requested") }
            bindSingleton<ToolInvocationRuntimeSandboxResolver>(overrides = true) { error("Sandbox resolver requested") }
        }.direct
        val store = di.instance<ConversationKnowledgeStore>()
        check(store is PostgresConversationKnowledgeStore)
        val chatId = UUID.fromString("95c3e969-01ef-4f41-9158-a45e643dcb21")
        val meta = ToolInvocationMeta("readonly-smoke", chatId.toString())
        val content = "head\u0000🙂" + "x".repeat(1_100_000) + "needle🙂tail"
        if (args[0] == "write") {
            PostgresUserRepository(dataSource).ensureUser(meta.userId)
            PostgresChatRepository(dataSource).create(Chat(
                id = chatId, userId = meta.userId, title = null, archived = false,
                clientType = "backend", requestId = "smoke", payloadHash = "smoke",
                createdAt = Instant.now(), updatedAt = Instant.now(),
            ))
            check(store.put(meta, "SmokeTool", content) is KnowledgeWriteResult.Stored)
        } else {
            val id = dataSource.read { connection ->
                connection.prepareStatement("select id from conversation_knowledge where user_id = ? and chat_id = ?").use {
                    it.setString(1, meta.userId)
                    it.setObject(2, chatId)
                    it.executeQuery().use { result ->
                        check(result.next())
                        result.getString("id").also { check(!result.next()) }
                    }
                }
            }
            check(store.get(meta, id)?.originalLength == content.length)
            val read = di.instance<ToolGetKnowledge>().invoke(
                LLMResponse.FunctionCall("GetKnowledge", mapOf("knowledgeId" to id)), meta,
            )
            val body = restJsonMapper.readTree(read.content)
            check(body["truncated"].asBoolean() && body["head"]["text"].asText().startsWith("head\u0000🙂"))
            val search = di.instance<ToolSearchKnowledge>().invoke(
                LLMResponse.FunctionCall("SearchKnowledge", mapOf("knowledgeId" to id, "regex" to "needle")), meta,
            )
            check(restJsonMapper.readTree(search.content)["matches"].single()["start"].asInt() == content.indexOf("needle"))
            store.clearConversation(meta)
            check(store.get(meta, id) == null)
        }
        println("Knowledge read-only smoke: ${args[0]} passed")
    }
}
