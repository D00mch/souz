@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
package ru.souz.backend.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import ru.souz.backend.app.BackendRuntimeResources
import ru.souz.backend.testutil.withPluginProbe
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta

class PluginLifecycleLivenessProcessTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    @Test fun cancellationCallbackReentryKeepsCoordinatorAndRecoveryLive() = probe("stop-callback")
    @Test fun blockedConstructorPreparationTimeoutKeepsCoordinatorAndRecoveryLive() = probe("prepare")
    @Test fun shutdownDuringBlockedPreparationRequestsRestartAndPreservesSharedServices() = probe("prepare-shutdown")
    @Test fun saturatedForeignWorkersDoNotStarveControlTimers() = probe("saturation")
    @Test fun incidentPersistenceBlackholeDoesNotDelayFatalSignalIndefinitely() = probe("incident-stall")
    @Test fun queuedPreparationAndCleanupHaveDeadlinesBeforeWorkerAdmission() = probe("queued-lifecycle")
    @Test fun sharedDefaultDispatcherCannotStarveControlAndRecovery() = probe("default-starvation")

    private fun probe(mode: String) = runtimeTest {
        val root = Files.createDirectories(directory.resolve(mode + "-" + UUID.randomUUID()))
        val source = if (mode.startsWith("prepare") || mode == "incident-stall") repackagePlugin(pluginFixture("kotlin-v1"), root.resolve("constructor.jar"),
            resources = mapOf("fixture-construction-block" to byteArrayOf())) else pluginFixture("kotlin-v1")
        val pin = inspectPlugin(source)
        artifacts.store(source, pin)
        repository.install(pin, 0)
        val next = if (mode == "queued-lifecycle") inspectPlugin(pluginFixture("kotlin-v2")).also {
            artifacts.store(pluginFixture("kotlin-v2"),it)
        } else null
        val idle = if (mode == "queued-lifecycle") inspectPlugin(pluginFixture("java")).also {
            artifacts.store(pluginFixture("java"),it)
        } else null
        val properties = Properties().apply {
            setProperty("jdbc", dataSource.jdbcUrl)
            setProperty("user", dataSource.username)
            setProperty("password", dataSource.password)
            setProperty("schema", dataSource.schema)
            setProperty("endpoint", artifactConfig.endpoint)
            setProperty("bucket", artifactConfig.bucket)
            setProperty("access", artifactConfig.accessKey)
            setProperty("secret", artifactConfig.secretKey)
            setProperty("digest", pin.digest)
            next?.let { setProperty("nextDigest",it.digest) }
            idle?.let { setProperty("idleDigest",it.digest) }
        }
        val config = root.resolve("configuration.properties")
        Files.newOutputStream(config).use { properties.store(it, null) }
        val output = root.resolve("process.log")
        try {
            withPluginProbe(PluginLifecycleLivenessProbe::class.java, listOf(config.toString(), mode), output) { process ->
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Probe did not exit: $mode")
                assertEquals(0, process.exitValue(), Files.readString(output).takeLast(5000))
                val proof = Files.readString(root.resolve("verified"))
                assertTrue(proof.isNotBlank())
            }
        } finally { Files.deleteIfExists(config) }
    }
}

object PluginLifecycleLivenessProbe {
    @JvmStatic fun main(args: Array<String>) {
      if (args[1] == "default-starvation") System.setProperty("kotlinx.coroutines.scheduler.core.pool.size","2")
      runBlocking {
        val configPath = Path.of(args[0])
        val root = configPath.parent
        val p = Properties().apply { Files.newInputStream(configPath).use(::load) }
        val mode = args[1]
        val pgGate = if (mode == "incident-stall") PgResponseGate(p.getProperty("jdbc")) else null
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = pgGate?.url ?: p.getProperty("jdbc"); username = p.getProperty("user"); password = p.getProperty("password")
            schema = p.getProperty("schema"); addDataSourceProperty("currentSchema", schema); maximumPoolSize = 8
        })
        val repository = PostgresPluginRepository(ds)
        val artifacts = S3PluginArtifacts(S3PluginArtifactConfig(p.getProperty("endpoint"), p.getProperty("bucket"),
            p.getProperty("access"), p.getProperty("secret")))
        val services = runtimeServices(repository)
        val pin = PluginBinding("reference", "v1", p.getProperty("digest"))
        if (mode == "queued-lifecycle") repository.install(PluginBinding("java_reference","v1",p.getProperty("idleDigest")),0)
        val fatals = AtomicInteger()
        val timing = PluginRuntimeConfig(queueMillis = 10000, callMillis = if (mode == "stop-callback" || mode == "default-starvation") 250 else 15000,
            prepareMillis = if (mode == "prepare" || mode == "incident-stall" || mode == "queued-lifecycle") 5000 else 15000, renewMillis = 250, leaseMillis = 10000,
            graceMillis = 250, cleanupMillis = 250, shutdownMillis = 300)
        System.setProperty("plugin.fixture.gate", root.toString())
        val runtime = BackendPluginRuntime(repository, artifacts, services, timing, onFatal = { fatals.incrementAndGet() })
        pgGate?.arm("insert into jvm_plugin_incidents")
        runtime.reconcile()
        if (mode.startsWith("prepare") || mode == "incident-stall") {
            withTimeout(10000) { while (!Files.exists(root.resolve("entered"))) delay(10) }
            if (mode == "incident-stall") {
                withTimeout(8000) { pgGate!!.entered.await() }
                withTimeout(1500) { while (fatals.get() == 0) delay(10) }
                verified(root, "C13 severe-incident JDBC persistence blackholed: fatal signal still delivered after cleanup deadline")
            } else if (mode == "prepare") {
                withTimeout(8000) { while (fatals.get() == 0) delay(10) }
                withTimeout(1000) { runtime.reconcile() }
                val reason = ds.connection.use { c -> c.createStatement().use { s -> s.executeQuery(
                    "select reason_code from jvm_plugin_incidents").use { it.next(); it.getString(1) } } }
                check(reason == "plugin_prepare_stalled")
                verified(root, "C04 constructor ignores interruption: preparation deadline persists incident and signals recovery; coordinator remains responsive")
            } else {
                var closedShared = false
                val resources = BackendRuntimeResources(shutdownPlugins = runtime::shutdown,
                    pluginDidNotDrain = { fatals.incrementAndGet() }, closePluginServices = { closedShared = true })
                withTimeout(2000) { resources.shutdown() }
                check(fatals.get() == 1 && !closedShared)
                withTimeout(1000) { runtime.reconcile() }
                verified(root, "C03 blocked preparation cancelled by shutdown: returns false within shutdown bound; application requests restart and keeps shared services open")
            }
            Runtime.getRuntime().halt(0)
        }
        withTimeout(15000) { while (pin !in runtime.snapshot.defaultBindings ||
            mode == "queued-lifecycle" && runtime.snapshot.defaultBindings.size != 2) delay(10) }
        val first = runtime.acquire("alice", listOf(pin), PLUGIN_FIXTURE_TOOLS)
        val version = first.versions.single()
        val original = version.functions.single { it.schema.name.endsWith(".report") }
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        if (mode == "stop-callback") {
            val entered = CompletableDeferred<Unit>()
            val tool = original.copy(handler = { _, context ->
                currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                    runBlocking { context.log("cancellation cleanup") }
                }
                entered.complete(Unit)
                awaitCancellation()
            })
            observer.launch { runtime.invoke(first, version, tool, LLMResponse.FunctionCall(tool.schema.name, emptyMap()), ToolInvocationMeta("alice")) }
            withTimeout(5000) { entered.await() }
            delay(1200)
            val responsive = withTimeoutOrNull(500) { runtime.reconcile(); true } ?: false
            check(responsive)
            withTimeout(3000) { while (runtime.snapshot.status.single().versions.single().calls != 0 && fatals.get() == 0) delay(10) }
            verified(root, "C01 SDK service used by synchronous cancellation callback: coordinator stays responsive; actual call exits or bounded recovery fires")
            Runtime.getRuntime().halt(0)
        }
        if (mode == "default-starvation") {
            val started=AtomicInteger()
            val tool=original.copy(handler={_,_-> coroutineScope {
                repeat(2) { launch(Dispatchers.Default) {
                    started.incrementAndGet()
                    while (true) Thread.onSpinWait()
                } }
                awaitCancellation()
            } })
            val caller=observer.async { runtime.invoke(first,version,tool,
                LLMResponse.FunctionCall(tool.schema.name,emptyMap()),ToolInvocationMeta("alice")) }
            withTimeout(5000) { while (started.get()!=2) delay(10) }
            withTimeout(3000) { while (fatals.get() == 0) delay(10) }
            check(version.calls.size == 1)
            check(!caller.isCompleted)
            verified(root,"Two structured children occupy every Default worker, including the caller's dispatcher: invocation timeout and recovery run on reserved control capacity")
            Runtime.getRuntime().halt(0)
        }
        check(mode == "saturation" || mode == "queued-lifecycle")
        ds.connection.use { c -> c.createStatement().use { s ->
            s.executeUpdate((0 until 70).joinToString(",", "insert into users(id) values ") { "('audit-owner-$it')" })
        } }
        val started = AtomicInteger()
        val tool = original.copy(handler = { _, _ ->
            started.incrementAndGet()
            while (true) try { Thread.sleep(50) } catch (_: InterruptedException) {}
            @Suppress("UNREACHABLE_CODE") "{}"
        })
        val jobs = (0 until 70).map { index -> observer.launch {
            val binding = runtime.acquire("audit-owner-$index", listOf(pin), PLUGIN_FIXTURE_TOOLS)
            try { runtime.invoke(binding, binding.versions.single(), tool,
                LLMResponse.FunctionCall(tool.schema.name, emptyMap()), ToolInvocationMeta("audit-owner-$index")) }
            finally { binding.release() }
        } }
        withTimeout(10000) { while (started.get() < 64 || runtime.snapshot.status.sumOf { row -> row.versions.sumOf { it.calls } } < 70) delay(10) }
        withTimeout(1500) { runtime.reconcile() }
        if (mode == "queued-lifecycle") {
            repository.retire("java_reference","v1",1)
            repository.install(PluginBinding("reference","v2",p.getProperty("nextDigest")),1)
            runtime.reconcile()
            delay(6000)
            check(runtime.snapshot.status.single { it.id == "java_reference" }.versions.single().phase == "closing")
            check(runtime.snapshot.status.single { it.id == "reference" }.versions.single { it.binding.version == "v2" }.phase == "preparing")
            check(fatals.get() > 0)
            val reasons = ds.connection.use { c -> c.createStatement().use { statement -> statement.executeQuery("select reason_code from jvm_plugin_incidents").use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } } } }
            check("plugin_prepare_stalled" in reasons && "plugin_cleanup_stalled" in reasons)
            verified(root,"C23b 64 blocked workers: queued cleanup still closing after 6000ms (cleanup=250ms); queued preparation still preparing (prepare=5000ms); both lifecycle deadlines fire before a worker is free")
            Runtime.getRuntime().halt(0)
        }
        jobs.forEach { it.cancel() }
        withTimeout(4000) { while (fatals.get() == 0) delay(10) }
        verified(root, "C23 all 64 foreign workers blocked, 70 calls admitted: control reconciliation and grace incident still progress")
        Runtime.getRuntime().halt(0)
      }
    }
    private fun verified(root: Path, text: String) { Files.writeString(root.resolve("verified"), text) }
}
