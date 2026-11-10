package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.DeserializationFeature
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import ru.souz.backend.client.normalizeToolParameters
import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.api.PluginApi
import ru.souz.plugins.api.PluginContext
import ru.souz.plugins.api.SouzPlugin
import ru.souz.tool.ToolCategory

internal data class PluginFunction(val schema: LLMRequest.Function, val category: ToolCategory, val handler: suspend (String, PluginContext) -> String)

/** The runtime owns this handle BEFORE provider construction, including failed construction. */
internal class PluginCode(val path: Path, val binding: PluginBinding) : AutoCloseable {
    val loader = PluginClassLoader(path.toUri().toURL())
    fun functions(): List<PluginFunction> {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        try {
            val providers = ServiceLoader.load(SouzPlugin::class.java, loader).stream().toList()
            require(providers.size == 1 && providers.single().type().classLoader === loader)
            val tools = providers.single().get().tools.toList()
            require(tools.size in 1..64)
            var bytes = 0L
            val names = mutableSetOf<String>()
            return tools.map { tool ->
                val name = "plugin." + binding.id + "." + tool.name
                require(name.length <= 64 && tool.name.matches(Regex("[a-z][a-z0-9_]{0,39}")) && names.add(name))
                require(tool.description.isNotBlank() && tool.description.length <= 16_384)
                bytes += tool.description.toByteArray().size + tool.inputSchemaJson.toByteArray().size
                require(bytes <= PluginApi.MAX_JSON_BYTES)
                val schema = restJsonMapper.readerFor(LLMRequest.Parameters::class.java)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue<LLMRequest.Parameters>(tool.inputSchemaJson)
                PluginFunction(LLMRequest.Function(name, tool.description, normalizeToolParameters(schema)),
                    ToolCategory.valueOf(tool.category), tool.invoke)
            }
        } finally { thread.contextClassLoader = previous }
    }
    override fun close() { loader.close(); Files.deleteIfExists(path) }
}
