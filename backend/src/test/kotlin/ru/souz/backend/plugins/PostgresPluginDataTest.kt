package ru.souz.backend.plugins

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.DataChange
import ru.souz.plugins.PLUGIN_MAX_BYTES

class PostgresPluginDataTest {
    @Test fun `atomic updates isolate owners serialize absent keys and survive delete races`() = runTest {
        PostgresDataSourceFactory.create(postgresAppConfig(newPostgresSchema("plugin_data")).postgres).use { source ->
            val users = PostgresUserRepository(source)
            users.ensureUser("one"); users.ensureUser("two")
            val data = PostgresPluginData(source, "one", "mail")
            coroutineScope {
                (1..30).map { async { data.update("count") { DataChange(number((it?.asInt() ?: 0) + 1), Unit) } } }.awaitAll()
            }
            assertEquals(30, data.get("count")?.asInt())
            assertNull(PostgresPluginData(source, "two", "mail").get("count"))
            assertNull(PostgresPluginData(source, "one", "other").get("count"))
            assertFailsWith<IllegalStateException> { data.update<Unit>("count") { error("rollback") } }
            assertEquals(30, data.get("count")?.asInt())
            coroutineScope {
                val delete = async { data.delete("count") }
                val update = async { data.update("count") { DataChange(number((it?.asInt() ?: 0) + 1), Unit) } }
                delete.await(); update.await()
            }
            assertTrue(data.get("count") == null || data.get("count")?.asInt() == 1)
            data.delete("count")
            listOf("z", "a", "я").forEach { data.put(it, number(1)) }
            val first = data.list(limit = 2)
            assertEquals(listOf("a", "z"), first.records.map { it.key })
            assertEquals(listOf("я"), data.list(first.nextCursor).records.map { it.key })
            assertFailsWith<IllegalArgumentException> { data.put("oversized", restJsonMapper.nodeFactory.textNode("x".repeat(PLUGIN_MAX_BYTES))) }
            assertNull(data.get("oversized"))
            source.connection.use { it.prepareStatement("delete from users where id = ?").use { delete -> delete.setString(1, "one"); delete.executeUpdate() } }
            assertTrue(data.list().records.isEmpty())
        }
    }

    private fun number(value: Int) = restJsonMapper.nodeFactory.numberNode(value)
}
