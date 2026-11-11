package fixture

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.util.JsonRecyclerPools
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.function.Supplier
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import org.apache.commons.csv.CSVFormat
import ru.souz.plugins.api.PluginException
import ru.souz.plugins.api.PluginFailureKind
import ru.souz.plugins.api.PluginHttpRequest
import ru.souz.plugins.api.PluginTool
import ru.souz.plugins.api.SouzPlugin

/** A separately compiled artifact; none of these classes belong to the host test classpath. */
class ReferencePlugin : SouzPlugin {
    init {
        require(javaClass.classLoader.getResource("fixture-construction-fail") == null) { "Fixture preparation failure" }
        if (javaClass.classLoader.getResource("fixture-construction-block") != null) {
            val root = Path.of(requireNotNull(System.getProperty("plugin.fixture.gate")))
            Files.writeString(root.resolve("entered"), "provider construction")
            // Only a disposable JVM receives this marker resource. The parent enforces process exit.
            while (!Files.exists(root.resolve("release"))) {
                try { Thread.sleep(5) } catch (_: InterruptedException) { /* intentionally defective */ }
            }
        }
    }
    override val tools = listOf(
        PluginTool("report", "Read private resources", """{"type":"object","additionalProperties":false}""", "MAIL") { _, _ ->
            val version = version()
            val rows = CSVFormat.DEFAULT.parse(StringReader("a,b\nc,d")).use { it.records.size }
            val provider = ServiceLoader.load(Supplier::class.java).single().get()
            val hostVisible = Thread.currentThread().contextClassLoader.getResource("ru/souz/backend/app/BackendRuntime.class") != null
            mapper().writeValueAsString(mapOf("version" to version, "rows" to rows, "provider" to provider, "hostVisible" to hostVisible))
        },
        PluginTool("state", "Increment isolated state", """{"type":"object","properties":{"wait":{"type":"integer"}},"additionalProperties":false}""", "MAIL") { input, context ->
            val value = context.state.get("count")?.toInt() ?: 0
            delay(mapper().readTree(input).path("wait").asLong(30).coerceIn(1, 1000).milliseconds)
            context.state.put("count", (value + 1).toString())
            """{"count":${value + 1},"version":"${version()}"}"""
        },
        PluginTool("fetch", "Use bounded host HTTP", """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"],"additionalProperties":false}""", "MAIL") { input, context ->
            val url = mapper().readTree(input)["url"].asText()
            context.http.execute(PluginHttpRequest(url)).body.toString(Charsets.UTF_8)
        },
        PluginTool("fail", "Return a classified failure", """{"type":"object","properties":{"kind":{"type":"string"}},"required":["kind"],"additionalProperties":false}""", "MAIL") { input, _ ->
            val kind = mapper().readTree(input)["kind"].asText()
            if (kind == "unexpected") error("Private data that must not reach the tool result")
            if (kind == "assertion") throw AssertionError("Private assertion data that must not reach the tool result")
            throw PluginException("fixture_failure", "Fixture operation failed", PluginFailureKind.valueOf(kind))
        },
        PluginTool("block", "Disposable-process non-cooperative fixture", """{"type":"object","additionalProperties":false}""", "MAIL") { _, context ->
            val root = Path.of(requireNotNull(System.getProperty("plugin.fixture.gate")))
            Files.writeString(root.resolve("entered"), context.meta.userId)
            // This fixture is invoked only in disposable JVMs. The parent owns the stop file.
            while (!Files.exists(root.resolve("release"))) {
                try { Thread.sleep(5) } catch (_: InterruptedException) { /* intentionally defective */ }
            }
            val value = version()
            Files.writeString(root.resolve("revoked"), "true")
            try {
                context.state.put("late", "true")
                Files.writeString(root.resolve("revoked"), "false")
            } finally { Files.writeString(root.resolve("exited"), value) }
            """{"version":"$value"}"""
        },
    )

    private fun mapper() = ObjectMapper(JsonFactory.builder().recyclerPool(JsonRecyclerPools.nonRecyclingPool()).build())

    private fun version(): String = requireNotNull(javaClass.classLoader.getResourceAsStream("fixture-version.txt"))
        .bufferedReader().use { it.readText().trim() }
}

class PrivateSupplier : Supplier<String> {
    override fun get(): String = "private-provider"
}
