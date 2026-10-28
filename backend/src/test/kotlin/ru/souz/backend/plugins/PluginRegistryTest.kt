package ru.souz.backend.plugins

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.io.TempDir

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginRegistryTest {
    @TempDir lateinit var root: Path

    @Test fun `loaded generations stay available during slow replacement and cancellation discards candidates`() = runTest {
        pluginJar(root)
        val registry = PluginRegistry(root)
        for (mode in listOf("cancel", "publish", "close")) {
            val first = registry.installed().single().binding
            val key = "souz.plugin.test.${UUID.randomUUID()}"
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            System.getProperties()[key] = Runnable {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
            }
            try {
                pluginJar(root, "two", initialize = "System.getProperties().put(\"$key.loader\", getClass().getClassLoader()); ((Runnable) System.getProperties().get(\"$key\")).run();")
                val scan = async {
                    try { registry.refresh(); null }
                    catch (e: CancellationException) { throw e }
                    catch (e: IllegalStateException) { e }
                }
                entered.await()
                val candidate = System.getProperties()["$key.loader"] as ClassLoader
                val lease = withTimeout(1_000) {
                    assertEquals(first, registry.installed().single().binding)
                    registry.acquire(listOf(first))
                }
                try {
                    if (mode == "cancel") scan.cancel()
                    val closing = if (mode == "close") async { registry.close() } else null
                    runCurrent()
                    release.countDown()
                    when (mode) {
                        "cancel" -> assertFailsWith<CancellationException> { scan.await() }
                        "close" -> { assertIs<IllegalStateException>(scan.await()); closing!!.await() }
                        else -> assertNull(scan.await())
                    }
                    if (mode != "close") assertEquals(if (mode == "cancel") "one" else "two", registry.installed().single().binding.version)
                    if (mode != "publish") assertNull(candidate.getResource("plugin-marker"))
                    assertNotNull(lease.tools.single().tool.invoke.javaClass.classLoader.getResource("plugin-marker"))
                } finally { lease.close() }
            } finally {
                release.countDown()
                System.getProperties().remove(key)
                System.getProperties().remove("$key.loader")
            }
        }
        registry.close()
    }

    @Test fun `application poller publishes updates and stops on shutdown`() = runTest {
        pluginJar(root)
        val registry = PluginRegistry(root)
        registry.start(backgroundScope)
        runCurrent()
        pluginJar(root, "two")
        advanceTimeBy(5_000)
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (registry.installed().single().binding.version != "two") delay(5) }
        }
        registry.close()
        advanceTimeBy(10_000)
    }

    @Test fun `invalid candidates log actionable installation diagnostics`() = runTest {
        pluginJar(root, api = "unsupported")
        val logger = LoggerFactory.getLogger(PluginRegistry::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val registry = PluginRegistry(root)
        try {
            assertTrue(registry.installed().isEmpty())
            val failure = appender.list.single { it.formattedMessage.contains("Incompatible plugin SDK") }
            assertTrue(failure.formattedMessage.contains("probe"))
            assertTrue(failure.formattedMessage.contains("unsupported"))
            assertNotNull(failure.throwableProxy)
        } finally { registry.close(); logger.detachAppender(appender); appender.stop() }
    }

    @Test fun `replacement removal and restart preserve pinned tools and retire unused loaders`() = runTest {
        pluginJar(root)
        val registry = PluginRegistry(root)
        val first = registry.installed().single().binding
        val lease = registry.acquire(listOf(first))
        val loader = lease.tools.single().tool.invoke.javaClass.classLoader
        assertNotNull(loader.getResource("plugin-marker"))
        pluginJar(root, "two")
        registry.refresh()
        assertEquals("two", registry.installed().single().binding.version)
        assertEquals("one", lease.tools.single().tool.function.description)
        Files.delete(root.resolve("probe.jar"))
        registry.refresh()
        assertTrue(registry.installed().isEmpty())
        lease.close()
        lease.close()
        assertNull(loader.getResource("plugin-marker"))
        registry.close()
        val resumed = PluginRegistry(root)
        val retained = resumed.acquire(listOf(first))
        assertEquals("one", retained.tools.single().tool.function.description)
        retained.close()
        Files.delete(root.resolve(".cache/${first.digest}.jar"))
        assertFailsWith<java.nio.file.NoSuchFileException> { resumed.acquire(listOf(first)) }
        resumed.close()
    }

    @Test fun `invalid replacements retain working version and filename owns installation identity`() = runTest {
        pluginJar(root)
        val registry = PluginRegistry(root)
        val original = registry.installed()
        pluginJar(root, "two", api = "unsupported")
        registry.refresh()
        assertEquals(original, registry.installed())
        for (invalid in listOf("category", "schema", "name")) {
            pluginJar(root, "invalid", category = if (invalid == "category") "UNKNOWN" else "NOTES",
                schemaType = if (invalid == "schema") "string" else "object",
                localName = if (invalid == "name") "invalid-name" else "version")
            registry.refresh()
            assertEquals(original, registry.installed(), invalid)
        }
        Files.writeString(root.resolve("probe.jar"), "incomplete")
        pluginJar(root, id = "other")
        registry.refresh()
        assertEquals(setOf("probe", "other"), registry.installed().map { it.binding.id }.toSet())
        Files.delete(root.resolve("other.jar"))
        registry.refresh()
        assertEquals(original, registry.installed())
        registry.close()
        assertFailsWith<IllegalStateException> { registry.installed() }
        pluginJar(root)
        val reserved = PluginRegistry(root, reservedNames = setOf("probe__version"))
        assertTrue(reserved.installed().isEmpty())
        reserved.close()
    }

    @Test fun `failed acquisition releases partial loads and rejects duplicate bindings`() = runTest {
        pluginJar(root)
        val registry = PluginRegistry(root)
        val binding = registry.installed().single().binding
        assertFailsWith<IllegalArgumentException> { registry.acquire(listOf(binding, binding)) }
        assertFailsWith<IllegalArgumentException> { registry.acquire(listOf(binding.copy(version = "wrong"))) }
        val lease = registry.acquire(listOf(binding))
        registry.close()
        assertNotNull(lease.tools.single().tool.invoke.javaClass.classLoader.getResource("plugin-marker"))
        lease.close()
        assertNull(lease.tools.single().tool.invoke.javaClass.classLoader.getResource("plugin-marker"))
    }
}
