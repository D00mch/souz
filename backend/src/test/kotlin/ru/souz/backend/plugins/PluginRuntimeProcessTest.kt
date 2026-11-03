package ru.souz.backend.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/** Defective foreign code never runs in the Gradle test JVM. */
class PluginRuntimeProcessTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `timed out code retains its loader and lease until actual completion`(): Unit = runBlocking {
        val fixture = PluginRuntimeFixture()
        try {
            val binding = fixture.install("kotlin-v1", 0)
            runProbe(fixture, binding, "drain", 0)
            assertEquals(0, fixture.repository.version(binding)!!.severeIncidents)
        } finally { fixture.close() }
    }

    @Test
    fun `lease loss revokes actual old code and its eventual release cannot clear the successor`(): Unit = runBlocking {
        val fixture = PluginRuntimeFixture()
        try {
            runProbe(fixture, fixture.install("kotlin-v1", 0), "lost", 0)
        } finally { fixture.close() }
    }

    @Test
    fun `stuck provider construction triggers persistent diagnostics and process recovery`(): Unit = runBlocking {
        val fixture = PluginRuntimeFixture()
        try {
            val source = repackagePlugin(pluginFixture("kotlin-v1"), directory.resolve("stuck-provider.jar"),
                resources = mapOf("fixture-construction-block" to byteArrayOf()))
            val binding = PluginArtifact.inspect(source).binding
            fixture.artifacts.store(source, binding)
            fixture.repository.install(binding, 0)
            runProbe(fixture, binding, "prepare", 71)
            assertIncident(fixture, binding, "plugin_prepare_stalled")
        } finally { fixture.close() }
    }

    @Test
    fun `failed artifact cleanup triggers persistent diagnostics and process recovery`(): Unit = runBlocking {
        val fixture = PluginRuntimeFixture()
        try {
            val binding = fixture.install("kotlin-v1", 0)
            runProbe(fixture, binding, "cleanup", 71)
            assertIncident(fixture, binding, "plugin_cleanup_failed")
        } finally { fixture.close() }
    }

    @Test
    fun `stuck work persists one severe incident per process and quarantines across restarts`(): Unit = runBlocking {
        val fixture = PluginRuntimeFixture()
        try {
            val binding = fixture.install("kotlin-v1", 0)
            repeat(3) { index ->
                runProbe(fixture, binding, "fatal", 71, index)
                val version = fixture.repository.version(binding)!!
                assertEquals(index + 1, version.severeIncidents)
                assertEquals(index == 2, version.quarantined)
                // Crash recovery: explicitly expire the dead process's lease rather than sleeping.
                fixture.dataSource.connection.use { connection ->
                    connection.createStatement().use {
                        it.executeUpdate("update jvm_plugin_calls set lease_until = clock_timestamp() - interval '1 second'")
                    }
                }
            }
            fixture.dataSource.connection.use { connection ->
                connection.createStatement().use { query ->
                    query.executeQuery("select count(*), count(distinct replica_id) from jvm_plugin_incidents where reason_code = 'plugin_call_stalled'").use {
                        assertTrue(it.next()); assertEquals(3, it.getInt(1)); assertEquals(3, it.getInt(2))
                    }
                }
            }
        } finally { fixture.close() }
    }

    private fun runProbe(fixture: PluginRuntimeFixture, binding: PluginBinding, mode: String, expectedExit: Int, index: Int = 0) {
        val root = Files.createDirectories(directory.resolve("$mode-$index"))
        val config = root.resolve("configuration.properties")
        val properties = Properties().apply {
            setProperty("jdbc", fixture.dataSource.jdbcUrl)
            setProperty("databaseUser", fixture.dataSource.username)
            setProperty("databasePassword", fixture.dataSource.password)
            setProperty("schema", fixture.dataSource.schema)
            setProperty("endpoint", fixture.artifactConfig.endpoint)
            setProperty("bucket", fixture.artifactConfig.bucket)
            setProperty("accessKey", fixture.artifactConfig.accessKey)
            setProperty("secretKey", fixture.artifactConfig.secretKey)
            setProperty("digest", binding.digest)
        }
        Files.newOutputStream(config).use { properties.store(it, null) }
        val output = root.resolve("process.log")
        val classpath = Files.readString(Path.of(requireNotNull(System.getProperty("plugin.host.classpath.file"))))
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx256m", "-cp", classpath, PluginRuntimeProbe::class.java.name, config.toString(), mode)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(Duration.ofSeconds(35).toMillis(), TimeUnit.MILLISECONDS), "Disposable plugin JVM did not exit: ${Files.readString(output)}")
            assertEquals(expectedExit, process.exitValue(), Files.readString(output))
            assertTrue(Files.exists(root.resolve("verified")), Files.readString(output))
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(config)
        }
    }

    private suspend fun assertIncident(fixture: PluginRuntimeFixture, binding: PluginBinding, code: String) {
        assertEquals(1, fixture.repository.version(binding)!!.severeIncidents)
        fixture.dataSource.connection.use { connection ->
            connection.prepareStatement("select reason_code, replica_id from jvm_plugin_incidents where plugin_id = ? and digest = ?").use {
                it.setString(1, binding.id); it.setString(2, binding.digest)
                it.executeQuery().use { row ->
                    assertTrue(row.next()); assertEquals(code, row.getString(1)); assertTrue(row.getString(2).isNotBlank()); assertFalse(row.next())
                }
            }
        }
    }
}

/** Entry point deliberately outside JUnit, launched only through [PluginRuntimeProcessTest]. */
object PluginRuntimeProbe {
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val path = Path.of(args[0])
        val root = path.parent
        val properties = Properties().apply { Files.newInputStream(path).use(::load) }
        val datasource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = properties.getProperty("jdbc")
            username = properties.getProperty("databaseUser")
            password = properties.getProperty("databasePassword")
            schema = properties.getProperty("schema")
            addDataSourceProperty("currentSchema", schema)
            maximumPoolSize = 5
        })
        val repository = PostgresPluginRepository(datasource)
        val artifacts = S3PluginArtifacts(S3PluginArtifactConfig(properties.getProperty("endpoint"), properties.getProperty("bucket"),
            properties.getProperty("accessKey"), properties.getProperty("secretKey")))
        val http = PluginHttpClient()
        val binding = PluginBinding("reference", "v1", properties.getProperty("digest"))
        val preparing = args[1] == "prepare"
        val cleanup = args[1] == "cleanup"
        val fatal = args[1] == "fatal" || preparing || cleanup
        val lost = args[1] == "lost"
        System.setProperty("plugin.fixture.gate", root.toString())
        val config = PluginRuntimeConfig(queueMillis = 300, callMillis = if (lost) 10_000 else 300, leaseMillis = 1_000, renewMillis = 100,
            graceMillis = if (fatal) 200 else 10_000, shutdownMillis = 1_000, prepareMillis = if (preparing) 5_000 else 15_000)
        val workDirectory = Files.createDirectories(root.resolve("loader"))
        val runtime = BackendPluginRuntime(repository, artifacts, runtimeServices(repository, http), config, onFatal = {
            check(fatal) { "Unexpected fatal signal during drain probe." }
            Files.writeString(root.resolve("verified"), "severe persisted before fatal restart")
            Runtime.getRuntime().halt(71)
        }, directory = workDirectory)
        val peer = BackendPluginRuntime(repository, artifacts, runtimeServices(repository, http), config)
        try {
            runtime.reconcile()
            if (preparing) {
                awaitPlugin { Files.exists(root.resolve("entered")) }
                awaitCancellation()
            }
            val pinned = runtime.acquire("alice", listOf(binding), PLUGIN_FIXTURE_TOOLS)
            if (cleanup) {
                pinned.release()
                val jar = Files.list(workDirectory).use { paths -> paths.filter { it.toString().endsWith(".jar") }.toList().single() }
                // A deterministic filesystem fault after loading, without changing the immutable S3 artifact.
                Files.delete(jar)
                Files.createDirectory(jar)
                Files.writeString(jar.resolve("obstruction"), "prevent artifact cleanup")
                repository.retire(binding.id, binding.version, 1)
                runtime.reconcile()
                awaitCancellation()
            }
            val pending = async { pinned.call("block") }
            awaitPlugin { Files.exists(root.resolve("entered")) }
            if (lost) {
                datasource.connection.use { connection -> connection.createStatement().use {
                    it.executeUpdate("update jvm_plugin_calls set lease_until = clock_timestamp() - interval '1 second'")
                } }
                val successor = requireNotNull(repository.claim("alice", binding, config))
                assertEquals("plugin_lease_lost", pending.await().errorCode())
                repository.assertOwned(successor)
                Files.writeString(root.resolve("release"), "release after ownership loss")
                awaitPlugin { Files.exists(root.resolve("exited")) && runtime.snapshot.status.single().versions.single().calls == 0 }
                assertEquals("true", Files.readString(root.resolve("revoked")))
                repository.assertOwned(successor)
                repository.release(successor)
                pinned.release()
                assertTrue(runtime.shutdown())
                Files.writeString(root.resolve("verified"), "stale handler cannot dispatch or release successor")
                return@runBlocking
            }
            assertEquals("plugin_timeout", pending.await().errorCode())
            if (fatal) awaitCancellation()
            assertEquals(1, runtime.snapshot.status.single().versions.single().calls)
            pinned.release()
            repository.retire(binding.id, binding.version, 1)
            runtime.reconcile()
            assertEquals(1, runtime.snapshot.status.single().liveVersions)
            delay(1_200)
            assertEquals(null, repository.claim("alice", binding, config), "Timed-out actual code must retain renewable slot ownership.")
            val other = peer.acquire("alice", listOf(binding), PLUGIN_FIXTURE_TOOLS)
            assertEquals("plugin_queue_timeout", other.call("report").errorCode())
            assertFalse(runtime.shutdown(), "Host resources must remain open while actual code survives cancellation.")
            Files.writeString(root.resolve("release"), "release defective handler")
            awaitPlugin { Files.exists(root.resolve("exited")) && runtime.snapshot.status.single().liveVersions == 0 }
            assertEquals("v1", Files.readString(root.resolve("exited")))
            assertEquals("true", Files.readString(root.resolve("revoked")))
            assertEquals("v1", other.call("report")["version"].asText())
            other.release()
            assertTrue(runtime.shutdown())
            assertTrue(peer.shutdown())
            Files.writeString(root.resolve("verified"), "actual code drained before resource cleanup")
        } finally {
            Files.writeString(root.resolve("release"), "cleanup")
            runtime.shutdown(); peer.shutdown()
            http.close(); artifacts.close(); datasource.close()
        }
    }
}
