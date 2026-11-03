package ru.souz.backend.plugins

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.ServiceLoader
import com.fasterxml.jackson.databind.DeserializationFeature
import ru.souz.backend.client.normalizeToolParameters
import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.api.PluginApi
import ru.souz.plugins.api.PluginContext
import ru.souz.plugins.api.SouzPlugin
import ru.souz.tool.ToolCategory

internal class PluginLoader(private val directory: Path) {
    /** Ownership is recorded by the caller before returning to a cancellable context. */
    fun open(source: Path, required: PluginBinding? = null): OpenPlugin {
        Files.createDirectories(directory)
        val part = Files.createTempFile(directory, "plugin-", ".part")
        val path = part.resolveSibling(part.fileName.toString().removeSuffix(".part") + ".jar")
        var loader: PluginClassLoader? = null
        try {
            Files.copy(source, part, REPLACE_EXISTING)
            val artifact = PluginArtifact.inspect(part)
            require(required == null || required == artifact.binding) { "Plugin identity or digest mismatch." }
            Files.move(part, path, ATOMIC_MOVE)
            loader = PluginClassLoader(path.toUri().toURL())
            val plugin = withPluginLoader(loader) {
                val providers = ServiceLoader.load(SouzPlugin::class.java, loader).stream().toList()
                require(providers.size == 1 && providers.single().type().classLoader === loader) {
                    "Expected one plugin provider from this archive."
                }
                providers.single().get()
            }
            return OpenPlugin(artifact, path, loader, plugin)
        } catch (failure: Throwable) {
            try { loader?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            for (file in listOf(path, part)) {
                try { Files.deleteIfExists(file) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }
}

internal data class LoadedPluginTool(
    val schema: LLMRequest.Function,
    val category: ToolCategory,
    val handler: suspend (String, PluginContext) -> String,
)

internal class OpenPlugin(
    val artifact: PluginArtifact,
    val path: Path,
    val loader: PluginClassLoader,
    val plugin: SouzPlugin,
) : AutoCloseable {
    fun tools(): List<LoadedPluginTool> = withPluginLoader(loader) {
        val tools = plugin.tools
        require(tools.size in 1..64) { "A plugin must provide 1..64 tools." }
        val names = mutableSetOf<String>()
        var schemaBytes = 0L
        tools.map { tool ->
            val name = "plugin.${artifact.id}.${tool.name}"
            require(tool.name.matches(Regex("[a-z][a-z0-9_]{0,39}")) && name.length <= 64 && names.add(name)) {
                "Invalid or duplicate plugin tool name."
            }
            require(tool.description.isNotBlank() && tool.description.length <= 16_384) { "Invalid tool description." }
            schemaBytes += tool.inputSchemaJson.toByteArray().size + tool.description.toByteArray().size
            require(schemaBytes <= PluginApi.MAX_JSON_BYTES) { "Plugin catalog exceeds the size limit." }
            val category = ToolCategory.entries.firstOrNull { it.name == tool.category }
                ?: throw IllegalArgumentException("Unknown plugin tool category.")
            val parameters = restJsonMapper.readerFor(LLMRequest.Parameters::class.java)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue<LLMRequest.Parameters>(tool.inputSchemaJson)
            LoadedPluginTool(LLMRequest.Function(name, tool.description, normalizeToolParameters(parameters)), category, tool.invoke)
        }
    }

    override fun close() {
        loader.close()
        Files.deleteIfExists(path)
    }
}
