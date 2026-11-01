package fixture

import java.util.ServiceLoader
import java.util.function.Supplier
import java.util.jar.Manifest
import java.net.URI
import kotlinx.coroutines.yield
import org.apache.commons.csv.CSVFormat
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.plugins.api.PluginInvocation
import ru.souz.plugins.api.SkillPlugin
import ru.souz.llms.restJsonMapper

class ReferencePlugin : SkillPlugin {
    private val manifest = javaClass.classLoader.getResourceAsStream("META-INF/MANIFEST.MF")!!.use { Manifest(it).mainAttributes }
    private val id = manifest.getValue("Souz-Plugin-Id")
    private val version = manifest.getValue("Souz-Plugin-Version")
    private val mode = resource("fixture-mode.txt").orEmpty()
    private val report = function("report", mapOf("text" to "string", "hold" to "boolean"), listOf("text"))
    override val tools: List<LLMRequest.Function>
        get() {
            val result = listOf(
                if (mode == "schema") report.copy(parameters = LLMRequest.Parameters("object", mapOf("text" to LLMRequest.Property("bogus")))) else report,
                function("state", mapOf("key" to "string", "json" to "string", "revision" to "integer", "userId" to "string"), listOf("key")),
                function("fetch", mapOf("url" to "string"), listOf("url")),
            ) + if (version == "v2") listOf(function("report_v2", mapOf("document" to "string"), listOf("document"))) else emptyList()
            return if (mode == "duplicate") result + report else result
        }

    override suspend fun initialize() {
        signal("init")
        check(mode != "init-fail") { "Fixture partial initialization failure" }
    }
    override suspend fun shutdown() {
        signal("shutdown")
        check(mode != "cleanup-fail") { "Fixture defective cleanup" }
    }

    override suspend fun invoke(call: LLMResponse.FunctionCall, context: PluginInvocation): LLMRequest.Message {
        if (mode == "invoke-linkage") throw NoClassDefFoundError("fixture-missing-private-class")
        check(mode != "invoke-fail") { "Fixture ordinary invocation failure" }
        if (call.arguments["hold"] == true) signal("invoke")
        val result = when (call.name.substringAfterLast('.')) {
            "state" -> {
                val key = call.arguments.getValue("key") as String
                val stored = (call.arguments["json"] as? String)?.let {
                    context.state.put(key, it, 1, (call.arguments["revision"] as? Number)?.toLong())
                } ?: context.state.get(key)
                restJsonMapper.writeValueAsString(mapOf("owner" to context.meta.userId, "json" to stored?.json,
                    "revision" to stored?.revision, "version" to version))
            }
            "fetch" -> context.http.request("GET", call.arguments.getValue("url") as String, emptyMap(), null).body
            else -> report(call)
        }
        return LLMRequest.Message(LLMMessageRole.function, result, name = call.name)
    }

    private suspend fun report(call: LLMResponse.FunctionCall): String {
        val text = (call.arguments["text"] ?: call.arguments["document"]) as? String ?: error("text/document is required")
        yield()
        val version = ServiceLoader.load(Supplier::class.java).single().get()
        val count = CSVFormat.DEFAULT.parse(text.reader()).use { it.records.size }
        val hostVisible = listOf("ru.souz.backend.app.BackendRuntime", "ru.souz.llms.http.ProviderHttpClients")
            .any { name -> runCatching { javaClass.classLoader.loadClass(name) }.isSuccess }
        return "$version:$count:host=$hostVisible"
    }

    private fun function(tool: String, properties: Map<String, String>, required: List<String>) =
        LLMRequest.Function("plugin.$id.$tool", "Reference $tool capability",
            LLMRequest.Parameters("object", properties.mapValues { LLMRequest.Property(it.value) }, required, false))

    private fun resource(name: String): String? = javaClass.classLoader.getResource(name)?.readText()?.trim()

    private fun signal(event: String) {
        val base = resource("fixture-control-url.txt") ?: return
        val connection = URI("$base/$event").toURL().openConnection().apply {
            connectTimeout = 2_000
            readTimeout = 10_000
            useCaches = false
        }
        connection.getInputStream().use { it.readBytes() }
    }
}

class VersionSupplier : Supplier<String> {
    override fun get(): String = javaClass.classLoader.getResource("fixture-version.txt")!!.readText().trim()
}
