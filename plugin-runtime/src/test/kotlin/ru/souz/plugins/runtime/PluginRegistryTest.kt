package ru.souz.plugins.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import ru.souz.plugins.testing.PluginLifecycleProbe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.plugins.PluginException
import ru.souz.plugins.testing.pluginJar

class PluginRegistryTest {
    @TempDir lateinit var root: Path

    @Test fun `artifact failures preserve only their own identity and unrelated work proceeds`() = runTest {
        val broken = pluginJar(root.resolve("broken.jar"), id = "broken")
        val removed = pluginJar(root.resolve("removed.jar"), id = "removed")
        val healthy = pluginJar(root.resolve("healthy.jar"), id = "healthy")
        val diagnostics = mutableListOf<PluginDiagnostic>()
        val registry = PluginRegistry(root, reportFailure = diagnostics::add)
        try {
            val original = registry.installed().associate { it.binding.id to it.binding }
            Files.writeString(broken, "not a zip")
            Files.delete(removed)
            Files.writeString(root.resolve("unrelated.jar"), "not a plugin")
            pluginJar(healthy, id = "healthy", version = "2")
            registry.reconcile()
            assertEquals(setOf("broken", "healthy"), registry.installed().map { it.binding.id }.toSet())
            assertEquals(original["broken"], registry.installed().single { it.binding.id == "broken" }.binding)
            assertEquals("2", registry.installed().single { it.binding.id == "healthy" }.binding.version)
            assertTrue(diagnostics.any { it.path == broken.toString() && it.stage == "descriptor" })
            pluginJar(broken, id = "broken", version = "2")
            registry.reconcile()
            assertEquals(setOf("2"), registry.installed().map { it.binding.version }.toSet())
        } finally { registry.close() }
    }

    @Test fun `corrupt cache cannot block independent updates or removals and repaired bytes recover`() = runTest {
        val first = pluginJar(root.resolve("first.jar"), id = "first")
        val second = pluginJar(root.resolve("second.jar"), id = "second")
        val removed = pluginJar(root.resolve("removed.jar"), id = "removed")
        val registry = PluginRegistry(root)
        try {
            val binding = registry.installed().single { it.binding.id == "first" }.binding
            val cached = root.resolve(".cache/${binding.digest}.jar")
            Files.writeString(cached, "corruption")
            pluginJar(second, id = "second", version = "2")
            Files.delete(removed)
            registry.reconcile()
            assertEquals(setOf("first", "second"), registry.installed().map { it.binding.id }.toSet())
            assertEquals("2", registry.installed().single { it.binding.id == "second" }.binding.version)
            assertEquals("corruption", Files.readString(cached)) // Never overwrite an immutable artifact silently.
            Files.copy(first, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING) // Operator repair.
            registry.reconcile()
            registry.acquire(listOf(binding)).close()
        } finally { registry.close() }
    }

    @Test fun `part publication and failed directory read preserve registry state`() = runTest {
        val directory = Files.createDirectory(root.resolve("install"))
        val part = pluginJar(directory.resolve("plugin.jar.part"))
        val registry = PluginRegistry(directory, root.resolve("cache"))
        try {
            assertTrue(registry.installed().isEmpty())
            Files.move(part, directory.resolve("plugin.jar"), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            registry.reconcile()
            val original = registry.installed()
            Files.move(directory, root.resolve("moved"))
            assertFailsWith<java.nio.file.NoSuchFileException> { registry.reconcile() }
            assertEquals(original, registry.installed())
        } finally { registry.close() }
    }

    @Test fun `suspended lifecycle never blocks healthy inventory acquisition or release`() = runTest {
        pluginJar(root.resolve("healthy.jar"), id = "healthy")
        val registry = PluginRegistry(root)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        PluginLifecycleProbe.onStart = { entered.complete(Unit); resume.await() }
        try {
            val original = registry.installed().single().binding
            val lease = registry.acquire(listOf(original))
            pluginJar(root.resolve("slow.jar"), id = "slow",
                startBody = "return ru.souz.plugins.testing.PluginLifecycleProbe.start(c);")
            val update = async { registry.reconcile() }
            entered.await()
            assertEquals(listOf(original), registry.installed().map { it.binding })
            registry.acquire(listOf(original)).close()
            lease.close()
            resume.complete(Unit)
            update.await()
            assertEquals(2, registry.installed().size)
        } finally { resume.complete(Unit); registry.close(); PluginLifecycleProbe.onStart = {} }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `retired stop leaves admission available and shutdown joins in-flight cleanup`() = runTest {
        val stopping = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        PluginLifecycleProbe.onStop = { stopping.complete(Unit); resume.await() }
        val path = pluginJar(root.resolve("plugin.jar"), stopBody = "return ru.souz.plugins.testing.PluginLifecycleProbe.stop(c);")
        val registry = PluginRegistry(root)
        try {
            val binding = registry.installed().single().binding
            val old = registry.acquire(listOf(binding))
            pluginJar(path, version = "2")
            registry.reconcile()
            val release = async { old.close() }
            stopping.await()
            val current = registry.installed().single().binding
            assertEquals("2", current.version)
            val healthy = registry.acquire(listOf(current))
            val closing = async { registry.close() }
            runCurrent()
            assertFalse(closing.isCompleted)
            resume.complete(Unit)
            release.await()
            closing.await()
            healthy.close()
            assertFailsWith<IllegalStateException> { registry.acquire(listOf(current)) }
        } finally { resume.complete(Unit); registry.close(); PluginLifecycleProbe.onStop = {} }
    }

    @Test fun `cooperative lifecycle deadline cleans candidate and permits next artifact`() = runTest {
        val diagnostics = mutableListOf<PluginDiagnostic>()
        val stopped = CompletableDeferred<Unit>()
        PluginLifecycleProbe.onStart = { awaitCancellation() }
        PluginLifecycleProbe.onStop = { stopped.complete(Unit) }
        pluginJar(root.resolve("slow.jar"), id = "slow",
            startBody = "return ru.souz.plugins.testing.PluginLifecycleProbe.start(c);",
            stopBody = "return ru.souz.plugins.testing.PluginLifecycleProbe.stop(c);")
        pluginJar(root.resolve("valid.jar"), id = "valid")
        val registry = PluginRegistry(root, reportFailure = diagnostics::add, lifecycleTimeoutMs = 200)
        try {
            assertEquals(listOf("valid"), registry.installed().map { it.binding.id })
            stopped.await()
            assertTrue(diagnostics.any { it.reason.contains("start exceeded cooperative deadline") && it.binding?.id == "slow" })
        } finally { registry.close(); PluginLifecycleProbe.onStart = {}; PluginLifecycleProbe.onStop = {} }
    }

    @Test fun `replacement and removal preserve leased schemas until execution releases them`() = runTest {
        val stopped = root.resolve("stopped")
        val stop = "try { java.nio.file.Files.writeString(java.nio.file.Path.of(\"$stopped\"), \"closed\"); } catch (Exception e) { throw new RuntimeException(e); } return kotlin.Unit.INSTANCE;"
        val path = pluginJar(root.resolve("plugin.jar"), stopBody = stop)
        val registry = PluginRegistry(root)
        val original = registry.installed().single().binding
        val oldLease = registry.acquire(listOf(original))
        pluginJar(path, version = "2")
        registry.reconcile()
        val latest = registry.installed().single().binding
        assertEquals("2", latest.version)
        assertEquals("Report generation 1", oldLease.tools.single().definition.description)
        assertFalse(Files.exists(stopped))
        val secondOldLease = registry.acquire(listOf(original))
        oldLease.close()
        assertFalse(Files.exists(stopped))
        secondOldLease.close()
        secondOldLease.close()
        assertTrue(Files.exists(stopped))
        Files.delete(path)
        registry.reconcile()
        assertTrue(registry.installed().isEmpty())
        registry.close()
        // A waiting execution can reload its exact old artifact after registry restart.
        val restarted = PluginRegistry(root)
        val resumed = restarted.acquire(listOf(original))
        assertEquals("Report generation 1", resumed.tools.single().definition.description)
        resumed.close()
        Files.delete(stopped)
        // Failure after loading a cached generation must release it without an acquired lease.
        assertFailsWith<PluginException> {
            restarted.acquire(listOf(original, original.copy(id = "missing", digest = "0".repeat(64))))
        }
        assertTrue(Files.exists(stopped))
        Files.delete(root.resolve(".cache/${original.digest}.jar"))
        assertFailsWith<PluginException> { restarted.acquire(listOf(original)) }
        restarted.close()
    }

    @Test fun `invalid candidates and duplicate plugin ids retain last working version`() = runTest {
        val path = pluginJar(root.resolve("plugin.jar"))
        val failures = mutableListOf<String>()
        val registry = PluginRegistry(root, reportFailure = { failures += it.path })
        val original = registry.installed().single().binding
        for (candidate in listOf("sdk", "start", "tools", "manifest")) {
            when (candidate) {
                "sdk" -> pluginJar(path, version = "2", api = "99")
                "start" -> pluginJar(path, version = "2", startBody = "throw new IllegalStateException(\"failed\");")
                "tools" -> pluginJar(path, version = "2", duplicateTools = true)
                else -> Files.writeString(path, "unfinished upload")
            }
            registry.reconcile()
            assertEquals(original, registry.installed().single().binding)
        }
        pluginJar(path, id = "different", version = "2", api = "99")
        registry.reconcile()
        assertEquals(original, registry.installed().single().binding)
        pluginJar(path, version = "2")
        val duplicate = pluginJar(root.resolve("duplicate.jar"), version = "3")
        registry.reconcile()
        assertEquals(original, registry.installed().single().binding)
        assertEquals(6, failures.size)
        Files.delete(duplicate)
        registry.reconcile()
        assertEquals("2", registry.installed().single().binding.version)
        registry.close()
    }

    @Test fun `cancellation releases retired generation and leaves new generation usable`() = runTest {
        val path = pluginJar(root.resolve("plugin.jar"))
        val registry = PluginRegistry(root)
        val binding = registry.installed().single().binding
        val ready = CompletableDeferred<Unit>()
        val running = launch {
            val lease = registry.acquire(listOf(binding))
            try { ready.complete(Unit); awaitCancellation() } finally { lease.close() }
        }
        ready.await()
        pluginJar(path, version = "2")
        registry.reconcile()
        running.cancelAndJoin()
        val current = registry.acquire(listOf(registry.installed().single().binding))
        assertEquals("Report generation 2", current.tools.single().definition.description)
        current.close()
        registry.close()
    }

    @Test fun `reserved names and disabled directory never expose tools`() = runTest {
        pluginJar(root.resolve("plugin.jar"))
        val registry = PluginRegistry(root, reservedNames = setOf("fixture__version"))
        assertTrue(registry.installed().isEmpty())
        registry.close()
        val disabled = PluginRegistry()
        assertTrue(disabled.installed().isEmpty())
        disabled.close()
    }

    @Test fun `cancelled initialization stops the candidate and retains the active generation`() = runTest {
        val path = pluginJar(root.resolve("plugin.jar"))
        val registry = PluginRegistry(root)
        val original = registry.installed().single().binding
        val marker = root.resolve("cancelled-stop")
        pluginJar(path, version = "2", startBody = "throw new java.util.concurrent.CancellationException();",
            stopBody = "try { java.nio.file.Files.writeString(java.nio.file.Path.of(\"$marker\"), \"stopped\"); } catch (Exception e) { throw new RuntimeException(e); } return kotlin.Unit.INSTANCE;")
        assertFailsWith<CancellationException> { registry.reconcile() }
        assertTrue(Files.exists(marker))
        assertEquals(original, registry.installed().single().binding)
        registry.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `poller validates schema and cross-plugin namespaces before publication`() = runTest {
        pluginJar(root.resolve("one.jar"), id = "a", toolName = "b__c")
        val registry = PluginRegistry(root)
        registry.start(backgroundScope)
        runCurrent()
        val original = registry.installed().single().binding
        pluginJar(root.resolve("two.jar"), id = "a__b", toolName = "c")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(original), registry.installed().map { it.binding })
        Files.delete(root.resolve("two.jar"))
        pluginJar(root.resolve("one.jar"), id = "a", version = "2", schemaBody = "schema.put(\"additionalProperties\", false);")
        registry.reconcile()
        assertEquals(original, registry.installed().single().binding)
        registry.close()
        assertFailsWith<IllegalStateException> { registry.installed() }
    }
}
