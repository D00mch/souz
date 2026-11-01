package ru.souz.backend.plugins

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.ServiceLoader
import ru.souz.llms.LLMRequest
import ru.souz.plugins.api.SkillPlugin

/** Opening is synchronous and side-effect-free by SDK contract; initialization belongs to the runtime. */
internal class PluginLoader(private val directory: Path) {
    fun open(bytes: ByteArray, expected: PluginArtifact? = null): OpenPlugin {
        val artifact = PluginArtifact.inspect(bytes)
        require(expected == null || artifact == expected) { "Artifact identity or digest mismatch." }
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "${artifact.digest}-", ".part")
        val path = temporary.resolveSibling(temporary.fileName.toString().removeSuffix(".part") + ".jar")
        var loader: PluginClassLoader? = null
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, path, ATOMIC_MOVE)
            loader = PluginClassLoader(path.toUri().toURL())
            val plugin = withPluginLoader(loader) {
                val providers = ServiceLoader.load(SkillPlugin::class.java, loader).stream().toList()
                require(providers.size == 1) { "Expected exactly one SkillPlugin SPI provider." }
                require(providers.single().type().classLoader === loader) { "SPI provider must come from the artifact." }
                providers.single().get()
            }
            return OpenPlugin(artifact, path, loader, plugin)
        } catch (failure: Throwable) {
            try {
                loader?.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            for (file in listOf(path, temporary)) {
                try { Files.deleteIfExists(file) } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            }
            throw failure
        }
    }
}

internal class OpenPlugin(
    val artifact: PluginArtifact,
    val path: Path,
    val loader: PluginClassLoader,
    val plugin: SkillPlugin,
) : AutoCloseable {
    @Suppress("UNCHECKED_CAST")
    fun schemas(): List<LLMRequest.Function> = withPluginLoader(loader) {
        val tools = plugin.tools
        require(tools.size in 1..64) { "A plugin must provide 1..64 tools." }
        val prefix = "plugin.${artifact.id}."
        val names = mutableSetOf<String>()
        tools.map { fn ->
            require(fn.name.startsWith(prefix) && fn.name.removePrefix(prefix).matches(PluginArtifact.ID)) {
                "Tool name must be ${prefix}<tool_id>."
            }
            require(names.add(fn.name)) { "Duplicate plugin capability: ${fn.name}" }
            require(fn.description.length in 1..16_000) { "Invalid tool description length." }
            LLMRequest.Function(
                name = fn.name,
                description = fn.description,
                parameters = copyParameters(fn.parameters),
                fewShotExamples = fn.fewShotExamples?.map { example ->
                    LLMRequest.FewShotExample(example.request, copyPluginJson(example.params) as Map<String, Any>)
                },
                returnParameters = fn.returnParameters?.let(::copyParameters),
            )
        }
    }

    override fun close() {
        loader.close()
        Files.deleteIfExists(path)
    }
}

/** Do not let application-wide JSON serializers observe plugin-defined collection or DTO classes. */
@Suppress("UNCHECKED_CAST")
internal fun copyPluginJson(value: Any, depth: Int = 0): Any {
    require(depth < 24) { "Plugin JSON is too deeply nested." }
    return when (value) {
        is String, is Boolean, is Int, is Long, is Short, is Byte -> value
        is Double -> value.also { require(it.isFinite()) }
        is Float -> value.also { require(it.isFinite()) }
        is java.math.BigInteger -> java.math.BigInteger(value.toString())
        is java.math.BigDecimal -> java.math.BigDecimal(value.toString())
        is List<*> -> {
            require(value.size <= 16_384) { "Plugin JSON collection is too large." }
            value.map { it?.let { copyPluginJson(it, depth + 1) } }
        }
        is Map<*, *> -> {
            require(value.size <= 16_384) { "Plugin JSON collection is too large." }
            value.entries.associate { (key, item) ->
                require(key is String) { "JSON object keys must be strings." }
                key to item?.let { copyPluginJson(it, depth + 1) }
            }
        }
        else -> throw IllegalArgumentException("Plugin values must contain only plain JSON.")
    }
}

private fun copyParameters(value: LLMRequest.Parameters): LLMRequest.Parameters {
    require(value.type == "object" && value.properties.size <= 128) { "Invalid plugin parameters." }
    require(value.required.all { it in value.properties }) { "Unknown required parameter." }
    return LLMRequest.Parameters(
        type = value.type,
        properties = value.properties.mapValues { copyProperty(it.value, 0) },
        required = value.required.toList(),
        additionalProperties = value.additionalProperties,
    )
}

private fun copyProperty(value: LLMRequest.Property, depth: Int): LLMRequest.Property {
    require(depth < 16 && value.type.length <= 32 && (value.description?.length ?: 0) <= 16_000) { "Invalid plugin property." }
    require((value.properties?.size ?: 0) <= 128 && (value.enum?.size ?: 0) <= 128) { "Plugin schema is too large." }
    return LLMRequest.Property(
        type = value.type,
        description = value.description,
        enum = value.enum?.toList(),
        items = value.items?.let { copyProperty(it, depth + 1) },
        properties = value.properties?.mapValues { copyProperty(it.value, depth + 1) },
        required = value.required?.toList(),
        additionalProperties = value.additionalProperties,
    )
}
