package ru.souz.plugins.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
        val registry = PluginRegistry(root, reportFailure = { id, _ -> failures += id })
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
        pluginJar(path, version = "2")
        pluginJar(root.resolve("duplicate.jar"), version = "3")
        registry.reconcile()
        assertEquals(original, registry.installed().single().binding)
        assertEquals(5, failures.size)
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
