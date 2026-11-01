package ru.souz.backend.plugins

import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.souz.backend.app.BackendPostgresConfig
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta

/** A real second JVM, also producing a small repeatable reference workload for #958. */
object PluginRecoveryProbe {
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        fun env(key: String) = requireNotNull(System.getenv("PROBE_$key"))
        val config = BackendPostgresConfig("unused", 5432, "unused", env("USER"), env("PASSWORD"), env("SCHEMA"), 4, 5_000, env("URL"))
        PostgresDataSourceFactory.create(config).use { source ->
            val brokenCleanup = System.getenv("PROBE_MODE") == "cleanup-failure"
            val runtime = BackendPluginRuntime(PostgresPluginRepository(source),
                PluginRuntimeConfig(shutdownMillis = if (brokenCleanup) 500 else 30_000), directory = Path.of(env("DIRECTORY")))
            val memory = ManagementFactory.getMemoryMXBean()
            val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val beforeMemory = memory.heapMemoryUsage.used
            val started = System.nanoTime()
            withTimeout(10_000) {
                while (runtime.snapshot.status.singleOrNull()?.appliedDigest != env("DIGEST")) {
                    runtime.reconcile()
                    delay(10)
                }
            }
            if (brokenCleanup) {
                val failure = try { runtime.shutdown(); null } catch (expected: IllegalStateException) { expected }
                check(failure is IllegalStateException)
                check(runtime.snapshot.status.single().error!!.startsWith("plugin_cleanup_failed"))
                check(Files.list(Path.of(env("DIRECTORY"))).use { it.count() } == 1L)
                println("PLUGIN_CLEANUP_FAILURE digest=${env("DIGEST")} retained=true restart_required=true")
                return@use // A deliberately defective plugin is confined to this disposable JVM.
            }
            try {
                val loaded = System.nanoTime()
                val binding = runtime.acquire("alice")
                try {
                    val name = "plugin.reference.report"
                    val tool = binding.catalog.toolsByCategory.values.firstNotNullOf { it[name] }
                    val call = LLMResponse.FunctionCall(name, mapOf("text" to "a,b\nc,d"))
                    check(tool.invoke(call, ToolInvocationMeta("alice")).content.startsWith("v2:2:"))
                    val cpu = os.processCpuTime
                    val invocationStarted = System.nanoTime()
                    repeat(100) { check(tool.invoke(call, ToolInvocationMeta("alice")).content.startsWith("v2:2:")) }
                    val elapsed = System.nanoTime() - invocationStarted
                    val afterMemory = memory.heapMemoryUsage.used
                    println("PLUGIN_REFERENCE digest=${env("DIGEST")} load_ms=${(loaded - started) / 1_000_000.0} " +
                        "calls=100 elapsed_ms=${elapsed / 1_000_000.0} cpu_ms=${(os.processCpuTime - cpu) / 1_000_000.0} " +
                        "heap_before_bytes=$beforeMemory heap_after_bytes=$afterMemory heap_delta_bytes=${afterMemory - beforeMemory}")
                } finally { binding.release() }
            } finally { runtime.shutdown() }
        }
    }
}
