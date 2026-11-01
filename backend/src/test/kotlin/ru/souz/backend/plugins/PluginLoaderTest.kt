package ru.souz.backend.plugins

import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Supplier
import java.util.ServiceConfigurationError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.plugins.api.PluginHttp
import ru.souz.plugins.api.PluginHttpResponse
import ru.souz.plugins.api.PluginInvocation
import ru.souz.plugins.api.PluginLog
import ru.souz.plugins.api.PluginState
import ru.souz.plugins.api.SkillPlugin

class PluginLoaderTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `versions keep private dependencies and SPI resources isolated`() = runBlocking {
        val first = load("kotlin-v1")
        val second = load("kotlin-v2")
        first.use { v1 -> second.use { v2 ->
            assertNotSame(v1.plugin.javaClass, v2.plugin.javaClass)
            val csvClass = "org.apache.commons.csv.CSVFormat"
            assertNotSame(v1.loader.loadClass(csvClass), v2.loader.loadClass(csvClass))
            assertNotSame(org.apache.commons.csv.CSVFormat::class.java, v1.loader.loadClass(csvClass))
            assertEquals(SkillPlugin::class.java, v1.loader.loadClass(SkillPlugin::class.java.name))
            for ((plugin, version) in listOf(v1 to "v1", v2 to "v2")) {
                assertEquals("plugin.reference.report", plugin.schemas().single().name)
                withContext(PluginClassLoaderContext(plugin.loader)) {
                    plugin.plugin.initialize()
                    val result = plugin.plugin.invoke(LLMResponse.FunctionCall("plugin.reference.report", mapOf("text" to "a,b\nc,d")), context())
                    assertEquals("$version:2:backend=false", result.content)
                    plugin.plugin.shutdown()
                }
            }
        } }
        assertEquals(0, Files.list(directory).use { it.count() })
    }

    @Test
    fun `Java suspends and propagates cancellation through the shared SDK`() = runBlocking {
        load("java").use { opened ->
            val entered = CompletableDeferred<Unit>()
            var cancel = false
            val http = object : PluginHttp {
                override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): PluginHttpResponse {
                    if (cancel) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    kotlinx.coroutines.delay(5)
                    return PluginHttpResponse(200, "java-completed")
                }
            }
            val call = LLMResponse.FunctionCall("plugin.java_reference.fetch", mapOf("url" to "http://localhost/"))
            assertEquals("java-completed", opened.plugin.invoke(call, context(http)).content)
            cancel = true
            val job = async { opened.plugin.invoke(call, context(http)) }
            entered.await()
            job.cancelAndJoin()
            assertEquals(true, job.isCancelled)
        }
    }

    @Test
    fun `invalid artifacts leave no working copies`() {
        assertFailsWith<IllegalArgumentException> { PluginLoader(directory).open(byteArrayOf(1, 2, 3)) }
        val bytes = Files.readAllBytes(Path.of(System.getProperty("plugin.fixture.kotlin-v1")))
        for ((attribute, invalid) in mapOf("Souz-Plugin-Api" to "unknown", "Souz-Plugin-Jvm" to "99",
            "Souz-Plugin-Id" to "../escape", "Class-Path" to "other.jar")) {
            assertFailsWith<IllegalArgumentException> {
                PluginLoader(directory).open(repackagePlugin(bytes, mapOf(attribute to invalid)))
            }
        }
        assertFailsWith<ServiceConfigurationError> {
            PluginLoader(directory).open(repackagePlugin(bytes,
                resources = mapOf("META-INF/services/ru.souz.plugins.api.SkillPlugin" to "missing.Provider")))
        }
        assertFailsWith<IllegalArgumentException> {
            PluginLoader(directory).open(repackagePlugin(bytes, mapOf("X-Description" to "x".repeat(100_000))))
        }
        PluginLoader(directory).open(bytes).use { assertEquals(1, it.schemas().size) }
        assertFalse(Files.list(directory).use { it.findAny().isPresent })
    }

    private fun load(name: String) = PluginLoader(directory).open(Files.readAllBytes(Path.of(System.getProperty("plugin.fixture.$name"))))

    private fun context(http: PluginHttp = object : PluginHttp {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?) = error("unused")
    }) = PluginInvocation(ToolInvocationMeta("owner"), object : PluginState {
        override suspend fun get(key: String) = error("unused")
        override suspend fun put(key: String, json: String, dataSchemaVersion: Int, expectedRevision: Long?) = error("unused")
    }, http, PluginLog {})
}

class HostSupplier : Supplier<String> {
    override fun get() = "host-provider-must-not-leak"
}
