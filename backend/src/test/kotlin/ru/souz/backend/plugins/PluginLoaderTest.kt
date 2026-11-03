package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Supplier
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.api.PluginContext
import ru.souz.plugins.api.PluginHttp
import ru.souz.plugins.api.PluginHttpResponse
import ru.souz.plugins.api.SouzPlugin

class PluginLoaderTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `private Jackson dependencies resources and providers coexist without host fallback`(): Unit = runBlocking {
        val loader = PluginLoader(directory.resolve("work"))
        loader.open(pluginFixture("kotlin-v1")).use { first ->
            loader.open(pluginFixture("kotlin-v2")).use { second ->
                assertNotSame(first.plugin.javaClass, second.plugin.javaClass)
                assertNotSame(first.loader.loadClass("org.apache.commons.csv.CSVFormat"), second.loader.loadClass("org.apache.commons.csv.CSVFormat"))
                assertNotSame(ObjectMapper::class.java, first.loader.loadClass(ObjectMapper::class.java.name))
                assertEquals(SouzPlugin::class.java, first.loader.loadClass(SouzPlugin::class.java.name))
                assertFailsWith<ClassNotFoundException> { first.loader.loadClass("ru.souz.backend.app.BackendRuntime") }
                for ((opened, version) in listOf(first to "v1", second to "v2")) {
                    val tool = opened.tools().single { it.schema.name == "plugin.reference.report" }
                    val output = withContext(PluginClassLoaderContext(opened.loader)) { tool.handler("{}", mockk()) }
                    val result = restJsonMapper.readTree(output)
                    assertEquals(version, result["version"].asText())
                    assertEquals(2, result["rows"].asInt())
                    assertEquals("private-provider", result["provider"].asText())
                    assertFalse(result["hostVisible"].asBoolean())
                }
            }
        }
        assertEquals(0, Files.list(directory.resolve("work")).use { it.count() })
    }

    @Test
    fun `Java continuation ABI completes and cancels through a host HTTP service`(): Unit = runBlocking {
        PluginLoader(directory.resolve("work")).open(pluginFixture("java")).use { opened ->
            val tool = opened.tools().single()
            val entered = CompletableDeferred<Unit>()
            var cancel = false
            val http = PluginHttp {
                if (cancel) { entered.complete(Unit); awaitCancellation() }
                delay(5)
                PluginHttpResponse(200, emptyMap(), "\"java-completed\"".toByteArray())
            }
            val context = mockk<PluginContext> { every { this@mockk.http } returns http }
            assertEquals("\"java-completed\"", tool.handler("""{"url":"http://localhost/"}""", context))
            cancel = true
            val call = async { tool.handler("""{"url":"http://localhost/"}""", context) }
            entered.await()
            call.cancelAndJoin()
            assertEquals(true, call.isCancelled)
        }
    }

    @Test
    fun `manifest SPI JVM and shared-class rejection execute no providers and leak no work files`() {
        val work = directory.resolve("work")
        val loader = PluginLoader(work)
        for ((key, value) in mapOf("Souz-Plugin-Api" to "1", "Souz-Plugin-Jvm" to "99",
            "Souz-Plugin-Id" to "../escape", "Class-Path" to "other.jar")) {
            val input = repackagePlugin(pluginFixture("kotlin-v1"), directory.resolve("invalid.jar"), mapOf(key to value))
            assertFailsWith<IllegalArgumentException> { loader.open(input) }
        }
        val missing = repackagePlugin(pluginFixture("kotlin-v1"), directory.resolve("missing.jar"),
            resources = mapOf(PluginArtifact.PROVIDER_RESOURCE to "missing.Provider".toByteArray()))
        assertFailsWith<IllegalArgumentException> { PluginArtifact.inspect(missing) }
        val duplicate = repackagePlugin(pluginFixture("kotlin-v1"), directory.resolve("duplicate.jar"),
            resources = mapOf(PluginArtifact.PROVIDER_RESOURCE to "fixture.ReferencePlugin\nfixture.ReferencePlugin".toByteArray()))
        assertFailsWith<IllegalArgumentException> { PluginArtifact.inspect(duplicate) }
        val shared = repackagePlugin(pluginFixture("kotlin-v1"), directory.resolve("shared.jar"),
            resources = mapOf("kotlin/Forbidden.class" to byteArrayOf()))
        assertFailsWith<IllegalArgumentException> { PluginArtifact.inspect(shared) }
        val hugeManifest = repackagePlugin(pluginFixture("kotlin-v1"), directory.resolve("manifest.jar"), mapOf("X-Long" to "x".repeat(100_000)))
        assertFailsWith<IllegalArgumentException> { PluginArtifact.inspect(hugeManifest) }
        val binding = PluginArtifact.inspect(pluginFixture("kotlin-v1")).binding
        val truncated = directory.resolve("truncated.jar")
        Files.write(truncated, Files.readAllBytes(pluginFixture("kotlin-v1")).dropLast(22).toByteArray())
        assertFailsWith<java.util.zip.ZipException> { PluginArtifact.inspect(truncated) }
        assertFailsWith<IllegalArgumentException> { loader.open(pluginFixture("kotlin-v2"), binding) }
        loader.open(pluginFixture("kotlin-v1"), binding).use { assertEquals(binding, it.artifact.binding) }
        assertEquals(0, Files.list(work).use { it.count() })
    }
}

internal fun pluginFixture(name: String): Path = Path.of(requireNotNull(System.getProperty("plugin.fixture.$name")))

internal fun repackagePlugin(
    source: Path,
    target: Path,
    attributes: Map<String, String> = emptyMap(),
    resources: Map<String, ByteArray> = emptyMap(),
): Path {
    val bytes = ByteArrayOutputStream().use { output ->
        Files.newInputStream(source).use { stream -> JarInputStream(stream).use { input ->
            val manifest = input.manifest
            attributes.forEach { (key, value) -> manifest.mainAttributes.putValue(key, value) }
            JarOutputStream(output).use { jar ->
                jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF").apply { time = 0 })
                manifest.write(jar)
                jar.closeEntry()
                while (true) {
                    val entry = input.nextJarEntry ?: break
                    if (entry.name in resources) continue
                    jar.putNextEntry(JarEntry(entry.name).apply { time = 0 })
                    input.copyTo(jar)
                    jar.closeEntry()
                }
                resources.forEach { (name, content) ->
                    jar.putNextEntry(JarEntry(name).apply { time = 0 })
                    jar.write(content)
                    jar.closeEntry()
                }
            }
        } }
        output.toByteArray()
    }
    Files.write(target, bytes)
    return target
}

class HostPluginSupplier : Supplier<String> {
    override fun get(): String = "host-provider-must-not-leak"
}
