package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.plugins.PLUGIN_MAX_BYTES

class PostgresPluginDataTest {
    @Test fun `scoped JSON supports atomic writes keyset pages bounds and user deletion`() = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("plugin_data"))
        PostgresDataSourceFactory.create(config.postgres).use { source ->
            val users = PostgresUserRepository(source)
            users.ensureUser("owner")
            users.ensureUser("foreign")
            val data = PostgresPluginData(source, "owner", "plugin")
            val foreign = PostgresPluginData(source, "foreign", "plugin")
            val otherPlugin = PostgresPluginData(source, "owner", "other")
            val json = JsonNodeFactory.instance
            coroutineScope {
                (1..16).map { number -> async { data.put("same-key", json.numberNode(number)) } }.awaitAll()
            }
            assertTrue(data.get("same-key")!!.asInt() in 1..16)
            assertEquals(1, data.list().records.size)
            assertNull(foreign.get("same-key"))
            assertNull(otherPlugin.get("same-key"))
            assertFalse(foreign.delete("same-key"))
            data.put("a", json.nullNode())
            data.put("b", json.objectNode().put("value", "retained"))
            val first = data.list(limit = 1)
            assertEquals(listOf("a"), first.records.map { it.key })
            val second = data.list(first.nextCursor, limit = 2)
            assertEquals(listOf("b", "same-key"), second.records.map { it.key })
            assertNull(second.nextCursor)
            assertTrue(data.get("a")!!.isNull)
            coroutineScope {
                (1..32).map { async { data.update("counter") { json.numberNode((it?.asInt() ?: 0) + 1) } } }.awaitAll()
            }
            assertEquals(32, data.get("counter")!!.asInt())
            assertFailsWith<IllegalStateException> { data.update("counter") { error("rollback") } }
            assertEquals(32, data.get("counter")!!.asInt())
            for (key in listOf("", "x".repeat(513), "bad\u0000key")) {
                assertFailsWith<IllegalArgumentException> { data.put(key, json.nullNode()) }
            }
            assertFailsWith<IllegalArgumentException> { data.put("oversized", json.textNode("x".repeat(PLUGIN_MAX_BYTES))) }
            assertNull(data.get("oversized"))
            assertFailsWith<IllegalArgumentException> { data.list(limit = 101) }
            source.connection.use { connection -> connection.prepareStatement("delete from users where id = ?").use {
                it.setString(1, "owner"); assertEquals(1, it.executeUpdate())
            } }
            assertTrue(data.list().records.isEmpty())
        }
    }
}
