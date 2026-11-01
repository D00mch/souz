package ru.souz.backend.plugins

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig

class PluginRepositoryTest {
    @Test
    fun `operator publishes selects and disables with revision fencing across connections`() = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("plugin_registry")).postgres
        PostgresDataSourceFactory.create(config).use { first ->
            val registry = PostgresPluginRepository(first)
            val operator = PluginOperator(registry)
            val v1 = Path.of(System.getProperty("plugin.fixture.kotlin-v1"))
            val v2 = Path.of(System.getProperty("plugin.fixture.kotlin-v2"))
            operator.execute(listOf("install", v1.toString(), "0"))
            val initial = registry.desired().single()
            assertEquals(1, initial.revision)
            assertEquals(PluginArtifact.inspect(Files.readAllBytes(v1)).digest, initial.digest)
            assertFailsWith<IllegalArgumentException> {
                registry.install(repackagePlugin(Files.readAllBytes(v1), mapOf("Implementation-Title" to "changed bytes")))
            }
            assertFailsWith<IllegalArgumentException> { operator.execute(listOf("install", v2.toString(), "0")) }
            operator.execute(listOf("select", "reference", "v2", "1"))
            PostgresDataSourceFactory.create(config).use { second ->
                val otherReplica = PostgresPluginRepository(second)
                val updated = otherReplica.desired().single()
                assertEquals(2, updated.revision)
                assertEquals(Files.readAllBytes(v2).toList(), otherReplica.artifact("reference", updated.digest).toList())
                otherReplica.disable("reference", 2)
                assertEquals(false, registry.desired().single().enabled)
            }
            assertFailsWith<IllegalArgumentException> { registry.select("reference", "v1", 2) }
            assertEquals(true, registry.select("reference", "v1", 3).enabled)
        }
        PostgresDataSourceFactory.create(config).use { restarted ->
            assertEquals(4, PostgresPluginRepository(restarted).desired().single().revision)
        }
    }

    @Test
    fun `state partitions and compare-and-set stay isolated under concurrency`(): Unit = runBlocking {
        PostgresDataSourceFactory.create(postgresAppConfig(newPostgresSchema("plugin_state")).postgres).use { source ->
            source.connection.use { connection -> connection.createStatement().use {
                it.execute("insert into users(id) values ('alice'), ('bob')")
            } }
            val repository = PostgresPluginRepository(source)
            val alice = repository.state("reference", "alice")
            val bob = repository.state("reference", "bob")
            val separatePlugin = repository.state("other_plugin", "alice")
            alice.put("same", "{\"value\":1}", 1, 0)
            bob.put("same", "{\"ownerId\":\"alice\",\"value\":2}", 1, 0)
            separatePlugin.put("same", "{\"value\":3}", 1, 0)
            val outcomes = coroutineScope {
                (2..3).map { value -> async { alice.put("same", "{\"value\":$value}", 1, 1) } }.awaitAll()
            }
            assertEquals(1, outcomes.count { it != null })
            assertEquals(2, alice.get("same")!!.revision)
            assertEquals(1, bob.get("same")!!.revision)
            assertEquals(1, separatePlugin.get("same")!!.revision)
            assertNull(alice.put("missing", "{}", 1, 1))
            assertNull(alice.put("same", "{}", 1, 0))
            assertNotNull(bob.get("same"))
            assertEquals(3, ru.souz.llms.restJsonMapper.readTree(separatePlugin.get("same")!!.json)["value"].asInt())
            assertFailsWith<IllegalArgumentException> { alice.put("same", " ".repeat(65_537), 1, null) }
            assertFailsWith<IllegalArgumentException> { alice.get("../" + "x".repeat(256)) }
        }
    }
}
