package ru.souz.backend.plugins

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta

class PluginReplicaTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `defective cleanup is diagnosed in an isolated process and operator selection permits fresh recovery`(): Unit = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("plugin_cleanup")).postgres
        PostgresDataSourceFactory.create(config).use { source ->
            val repository = PostgresPluginRepository(source)
            val bytes = repackagePlugin(Files.readAllBytes(Path.of(System.getProperty("plugin.fixture.kotlin-v2"))),
                attributes = mapOf("Souz-Plugin-Version" to "defective"), resources = mapOf("fixture-mode.txt" to "cleanup-fail"))
            repository.install(bytes)
            val broken = repository.select("reference", "defective", 0)
            freshJvm(config.jdbcUrl(), config.user, config.password.orEmpty(), config.schema, broken.digest, "cleanup-failure")
            repository.disable("reference", 1)
            repository.install(Files.readAllBytes(Path.of(System.getProperty("plugin.fixture.kotlin-v2"))))
            val recovered = repository.select("reference", "v2", 2)
            freshJvm(config.jdbcUrl(), config.user, config.password.orEmpty(), config.schema, recovered.digest)
        }
    }

    @Test
    fun `replicas keep last good versions through load failure and registry outage then recover in a fresh JVM`(): Unit = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("plugin_replicas")).postgres
        val role = "plugin_reader_" + UUID.randomUUID().toString().replace("-", "").take(12)
        PostgresDataSourceFactory.create(config).use { source ->
            val repository = PostgresPluginRepository(source)
            val operator = PluginOperator(repository)
            operator.execute(listOf("install", System.getProperty("plugin.fixture.kotlin-v1"), "0"))
            val v1 = repository.desired().single().digest
            fun sql(text: String) = source.connection.use { it.createStatement().use { statement -> statement.execute(text) } }
            sql("create role $role")
            sql("grant usage on schema \"${config.schema}\" to $role")
            sql("grant select on skill_plugin_artifacts, skill_plugin_desired to $role")
            sql("alter table skill_plugin_artifacts enable row level security")
            sql("create policy limited_artifact on skill_plugin_artifacts for select to $role using (digest = '$v1')")
            PostgresDataSourceFactory.create(config).use { peerSource ->
                // Restrict every existing pool connection after the ordinary host migrations.
                val connections = (1..config.maxPoolSize).map { peerSource.connection }
                try {
                    connections.forEach { connection ->
                        connection.createStatement().use { it.execute("set role $role") }
                    }
                } finally { connections.forEach { it.close() } }
                val timings = PluginRuntimeConfig(pollMillis = 25, retryMillis = 100, shutdownMillis = 5_000)
                val first = BackendPluginRuntime(repository, timings, directory.resolve("first"))
                val peer = BackendPluginRuntime(PostgresPluginRepository(peerSource), timings, directory.resolve("peer"))
                var held: PluginExecutionBinding? = null
                try {
                    first.start(this)
                    peer.start(this)
                    eventually { first.snapshot.status.singleOrNull()?.appliedDigest == v1 && peer.snapshot.status.singleOrNull()?.appliedDigest == v1 }
                    operator.execute(listOf("install", System.getProperty("plugin.fixture.kotlin-v2"), "1"))
                    val v2 = repository.desired().single().digest
                    eventually { first.snapshot.status.single().appliedDigest == v2 && peer.snapshot.status.single().error != null }
                    assertEquals(v1, peer.snapshot.status.single().appliedDigest)
                    assertTrue(call(first).startsWith("v2:"))
                    assertTrue(call(peer).startsWith("v1:"))

                    held = peer.acquire("alice")
                    sql("revoke select on skill_plugin_desired from $role")
                    eventually { peer.snapshot.registryError != null }
                    operator.execute(listOf("disable", "reference", "2"))
                    eventually { first.snapshot.catalog.toolsByCategory.values.all { it.isEmpty() } }
                    assertTrue(call(peer).startsWith("v1:"))
                    sql("drop policy limited_artifact on skill_plugin_artifacts")
                    sql("create policy limited_artifact on skill_plugin_artifacts for select to $role using (true)")
                    sql("grant select on skill_plugin_desired to $role")
                    eventually { peer.snapshot.registryError == null && peer.snapshot.enabledIds.isEmpty() }
                    val name = "plugin.reference.report"
                    val rejected = held.catalog.toolsByCategory.values.firstNotNullOf { it[name] }
                        .invoke(LLMResponse.FunctionCall(name, mapOf("text" to "a")), ToolInvocationMeta("alice"))
                    assertTrue(rejected.content.contains("plugin_disabled"))
                    held.release()
                    held = null
                    operator.execute(listOf("select", "reference", "v2", "3"))
                    eventually { peer.snapshot.status.single().appliedDigest == v2 && first.snapshot.status.single().appliedDigest == v2 }
                    assertTrue(call(peer).startsWith("v2:"))
                    freshJvm(config.jdbcUrl(), config.user, config.password.orEmpty(), config.schema, v2)
                } finally {
                    held?.release()
                    first.shutdown()
                    peer.shutdown()
                }
                assertFalse(Files.exists(directory.resolve("first")))
                assertFalse(Files.exists(directory.resolve("peer")))
            }
        }
    }

    private suspend fun call(runtime: BackendPluginRuntime): String {
        val binding = runtime.acquire("alice")
        try {
            val name = "plugin.reference.report"
            val tool = binding.catalog.toolsByCategory.values.firstNotNullOf { it[name] }
            return tool.invoke(LLMResponse.FunctionCall(name, mapOf("text" to "a,b\nc,d")), ToolInvocationMeta("alice")).content
        } finally { binding.release() }
    }

    private suspend fun freshJvm(url: String, user: String, password: String, schema: String, digest: String, mode: String = "reference") {
        val arguments = directory.resolve("probe.args")
        val output = directory.resolve("probe.log")
        fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val classpath = Files.readString(Path.of(System.getProperty("plugin.host.classpath.file")))
        Files.writeString(arguments, "-cp\n${quoted(classpath)}\nru.souz.backend.plugins.PluginRecoveryProbe\n")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(java, "@$arguments").redirectErrorStream(true).redirectOutput(output.toFile()).apply {
            environment().putAll(mapOf("PROBE_URL" to url, "PROBE_USER" to user, "PROBE_PASSWORD" to password,
                "PROBE_SCHEMA" to schema, "PROBE_DIGEST" to digest, "PROBE_MODE" to mode,
                "PROBE_DIRECTORY" to directory.resolve(mode).toString()))
        }.start()
        try {
            val finished = withContext(Dispatchers.IO) { process.waitFor(30, TimeUnit.SECONDS) }
            assertTrue(finished, "Fresh JVM recovery timed out.")
            val text = Files.readString(output)
            assertEquals(0, process.exitValue(), text.takeLast(2_000))
            val reference = text.lineSequence().single { it.startsWith(if (mode == "reference") "PLUGIN_REFERENCE " else "PLUGIN_CLEANUP_FAILURE ") }
            println(reference)
            assertTrue(reference.contains("digest=$digest"))
            assertEquals(mode != "reference", Files.exists(directory.resolve(mode)))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                withContext(Dispatchers.IO) { process.waitFor(5, TimeUnit.SECONDS) }
            }
            Files.deleteIfExists(arguments)
            Files.deleteIfExists(output)
        }
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }
}
