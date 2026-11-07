package ru.souz.backend.plugins

import com.sun.nio.file.ExtendedOpenOption
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
import ru.souz.backend.testutil.withPluginProbe

/** Defective foreign code never runs in the Gradle test JVM. */
class PluginRuntimeProcessTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `retired reference and packaged Gmail loaders collect with normal GC policy`(): Unit = runtimeTest {
        // Coverage instrumentation retains instrumented classes; observe production retention in an uninstrumented JVM.
        for ((index, name) in listOf("kotlin-v1", "gmail").withIndex()) {
            val pin = install(name, 0)
            repository.retire(pin.id, pin.version, 1)
            runProbe(this, pin, "collect", 0, index)
        }
    }

    @Test
    fun `isolated processes preserve actual work and recover from stuck preparation or cleanup`() {
        for (mode in listOf("drain", "lost", "prepare", "cleanup")) runtimeTest {
            val source = if (mode == "prepare") repackagePlugin(pluginFixture("kotlin-v1"), directory.resolve("stuck-provider.jar"),
                resources = mapOf("fixture-construction-block" to byteArrayOf())) else pluginFixture("kotlin-v1")
            val binding = inspectPlugin(source)
            artifacts.store(source, binding)
            repository.install(binding, 0)
            runProbe(this, binding, mode, if (mode == "prepare" || mode == "cleanup") 71 else 0)
            when (mode) {
                "prepare" -> assertIncident(this, binding, "plugin_prepare_stalled")
                "cleanup" -> assertIncident(this, binding, "plugin_cleanup_failed")
                else -> assertEquals(0, repository.version(binding)!!.severeIncidents)
            }
        }
    }

    @Test
    fun `stuck work persists one severe incident per process and quarantines across restarts`(): Unit = runtimeTest {
        val fixture = this
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
    }

    private fun runProbe(fixture: PluginFixture, binding: PluginBinding, mode: String, expectedExit: Int, index: Int = 0) {
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
            setProperty("id", binding.id)
            setProperty("version", binding.version)
        }
        Files.newOutputStream(config).use { properties.store(it, null) }
        val output = root.resolve("process.log")
        try {
            withPluginProbe(PluginRuntimeProbe::class.java, listOf(config.toString(), mode), output) { process ->
                assertTrue(process.waitFor(Duration.ofSeconds(35).toMillis(), TimeUnit.MILLISECONDS), "Disposable plugin JVM did not exit: ${Files.readString(output)}")
                assertEquals(expectedExit, process.exitValue(), Files.readString(output))
                assertTrue(Files.exists(root.resolve("verified")), Files.readString(output))
            }
        } finally {
            Files.deleteIfExists(config)
        }
    }

    private suspend fun assertIncident(fixture: PluginFixture, binding: PluginBinding, code: String) {
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
        val services = runtimeServices(repository)
        val binding = PluginBinding(properties.getProperty("id"), properties.getProperty("version"), properties.getProperty("digest"))
        val preparing = args[1] == "prepare"
        val cleanup = args[1] == "cleanup"
        val fatal = args[1] == "fatal" || preparing || cleanup
        val lost = args[1] == "lost"
        val collecting = args[1] == "collect"
        System.setProperty("plugin.fixture.gate", root.toString())
        val config = PluginRuntimeConfig(queueMillis = 300, callMillis = if (collecting) 5_000 else if (lost) 10_000 else 300, leaseMillis = 1_000, renewMillis = 100,
            graceMillis = if (fatal) 200 else 10_000, shutdownMillis = 1_000, prepareMillis = if (preparing) 5_000 else 15_000)
        val workDirectory = Files.createDirectories(root.resolve("loader"))
        val runtime = BackendPluginRuntime(repository, artifacts, services, config, onFatal = {
            check(fatal) { "Unexpected fatal signal during drain probe." }
            Files.writeString(root.resolve("verified"), "severe persisted before fatal restart")
            Runtime.getRuntime().halt(71)
        }, directory = workDirectory)
        val peer = BackendPluginRuntime(repository, artifacts, services, config)
        var cleanupObstruction: AutoCloseable? = null
        try {
            runtime.reconcile()
            if (collecting) {
                collectRetiredLoaders(runtime, binding, datasource)
                Files.writeString(root.resolve("verified"), "normal GC collects retired loaders and cleanup releases JARs/connections")
                return@runBlocking
            }
            if (preparing) {
                awaitPlugin { Files.exists(root.resolve("entered")) }
                awaitCancellation()
            }
            val pinned = runtime.acquire("alice", listOf(binding), PLUGIN_FIXTURE_TOOLS)
            if (cleanup) {
                pinned.release()
                val jar = Files.list(workDirectory).use { paths -> paths.filter { it.toString().endsWith(".jar") }.toList().single() }
                // Both faults survive loader.close(), without changing the immutable S3 artifact.
                if (System.getProperty("os.name").startsWith("Windows")) {
                    cleanupObstruction = Files.newByteChannel(jar, setOf(
                        StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE,
                    ))
                } else {
                    Files.delete(jar)
                    Files.createDirectory(jar)
                    Files.writeString(jar.resolve("obstruction"), "prevent artifact cleanup")
                }
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
            assertEquals(1, runtime.snapshot.status.single().versions.count { it.phase != "failed" })
            delay(1_200)
            assertEquals(null, repository.claim("alice", binding, config), "Timed-out actual code must retain renewable slot ownership.")
            val other = peer.acquire("alice", listOf(binding), PLUGIN_FIXTURE_TOOLS)
            assertEquals("plugin_queue_timeout", other.call("report").errorCode())
            assertFalse(runtime.shutdown(), "Host resources must remain open while actual code survives cancellation.")
            Files.writeString(root.resolve("release"), "release defective handler")
            awaitPlugin { Files.exists(root.resolve("exited")) && runtime.snapshot.status.single().versions.count { it.phase != "failed" } == 0 }
            assertEquals("v1", Files.readString(root.resolve("exited")))
            assertEquals("true", Files.readString(root.resolve("revoked")))
            assertEquals("v1", other.call("report")["version"].asText())
            other.release()
            assertTrue(runtime.shutdown())
            assertTrue(peer.shutdown())
            Files.writeString(root.resolve("verified"), "actual code drained before resource cleanup")
        } finally {
            cleanupObstruction?.close()
            Files.writeString(root.resolve("release"), "cleanup")
            runtime.shutdown(); peer.shutdown()
            services.close(); artifacts.close(); datasource.close()
        }
    }

    private suspend fun collectRetiredLoaders(runtime: BackendPluginRuntime, pin: PluginBinding, datasource: HikariDataSource) {
        val references = mutableListOf<java.lang.ref.WeakReference<ClassLoader>>()
        suspend fun cycle() {
            val gmail = pin.id == "gmail"
            val name = if (gmail) "plugin.gmail.configure" else "plugin.reference.report"
            val binding = runtime.acquire("alice", listOf(pin), setOf(name))
            references += java.lang.ref.WeakReference(binding.versions.single().code!!.loader)
            val path = binding.versions.single().path
            val tool = binding.catalog.toolsByCategory.values.flatMap { it.values }.single()
            val arguments = if (gmail) mapOf("config" to "gc", "mailboxEmail" to "gc@example.com",
                "topicName" to "projects/gc/topics/mail", "channelType" to "chat", "channelId" to "owned") else emptyMap()
            val reply = tool.invoke(ru.souz.llms.LLMResponse.FunctionCall(name, arguments), ru.souz.llms.ToolInvocationMeta("alice"))
            val result = ru.souz.llms.restJsonMapper.readTree(reply.content)
            assertEquals(if (gmail) "configured" else "v1", result.path(if (gmail) "status" else "version").asText())
            binding.release()
            awaitPlugin { runtime.snapshot.status.find { it.id == pin.id }!!.versions.isEmpty() }
            assertFalse(Files.exists(path))
        }
        repeat(4) { cycle() }
        val collected = kotlinx.coroutines.withTimeoutOrNull(10_000) {
            while (references.any { it.get() != null }) { System.gc(); delay(100) }
            true
        }
        assertTrue(collected == true, "${pin.id} retained ${references.count { it.get() != null }} loaders")
        assertEquals(0, datasource.hikariPoolMXBean.activeConnections)
    }
}
