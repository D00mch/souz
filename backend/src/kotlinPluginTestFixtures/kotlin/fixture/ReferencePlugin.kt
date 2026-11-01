package fixture

import java.util.ServiceLoader
import java.util.function.Supplier
import kotlinx.coroutines.delay
import org.apache.commons.csv.CSVFormat
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.plugins.api.PluginInvocation
import ru.souz.plugins.api.SkillPlugin

class ReferencePlugin : SkillPlugin {
    override val tools = listOf(LLMRequest.Function("plugin.reference.report", "Produce a CSV report",
        LLMRequest.Parameters("object", mapOf("text" to LLMRequest.Property("string")), listOf("text"))))
    override suspend fun initialize() {}
    override suspend fun shutdown() {}
    override suspend fun invoke(call: LLMResponse.FunctionCall, context: PluginInvocation): LLMRequest.Message {
        require(call.arguments["text"] is String) { "text must be a string" }
        delay(1)
        val version = ServiceLoader.load(Supplier::class.java).single().get()
        val count = CSVFormat.DEFAULT.parse((call.arguments.getValue("text") as String).reader()).use { it.records.size }
        val hostVisible = listOf("ru.souz.backend.app.BackendRuntime", "ru.souz.llms.http.ProviderHttpClients")
            .any { name -> runCatching { javaClass.classLoader.loadClass(name) }.isSuccess }
        return LLMRequest.Message(LLMMessageRole.function, "$version:$count:host=$hostVisible",
            name = call.name)
    }
}

class VersionSupplier : Supplier<String> {
    override fun get(): String = javaClass.classLoader.getResource("fixture-version.txt")!!.readText().trim()
}
