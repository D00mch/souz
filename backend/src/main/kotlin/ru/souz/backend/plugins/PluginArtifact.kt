package ru.souz.backend.plugins

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import ru.souz.plugins.api.PluginApi

internal const val PLUGIN_JAR_BYTES = 64 * 1024 * 1024
internal const val PLUGIN_SPI = "META-INF/services/ru.souz.plugins.api.SouzPlugin"

/** Bounds and identity are validated before any plugin class is linked or initialized. */
internal fun inspectPlugin(path: Path): PluginBinding {
    require(Files.size(path) in 1..PLUGIN_JAR_BYTES.toLong())
    val index = ZipFile(path.toFile()).use { zip ->
        buildSet { for (entry in zip.entries()) require(size < 20_000 && add(entry.name)) }
    }
    val names = mutableSetOf<String>()
    var provider: String? = null
    var attributes: java.util.jar.Attributes? = null
    var expanded = 0L
    Files.newInputStream(path).use { input -> ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(names.size < 20_000 && names.add(entry.name))
            val name = entry.name
            val className = name.replace(Regex("^META-INF/versions/[0-9]+/"), "").replace('/', '.')
            require(!name.endsWith(".class") || !isSharedPluginClass(className)) { "Shared runtime classes must be compile-only." }
            val limit = when (name) { "META-INF/MANIFEST.MF" -> 65_536; PLUGIN_SPI -> 8192; else -> 8 }
            val header = zip.readNBytes(limit + if (limit > 8) 1 else 0)
            expanded += header.size
            when (name) {
                "META-INF/MANIFEST.MF" -> {
                    require(header.size <= limit)
                    attributes = Manifest(header.inputStream()).mainAttributes
                }
                PLUGIN_SPI -> {
                    require(header.size <= limit)
                    val providers = header.toString(Charsets.UTF_8).lineSequence()
                        .map { it.substringBefore('#').trim() }.filter(String::isNotEmpty).toList()
                    require(providers.size == 1 && providers.single().matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]*")))
                    provider = providers.single()
                }
            }
            if (name.endsWith(".class")) {
                require(header.size == 8 && header.take(4) == listOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte()))
                require((header[6].toInt() and 255) * 256 + (header[7].toInt() and 255) <= 65) { "Plugin requires a newer JVM." }
            }
            val buffer = ByteArray(8192)
            do {
                require(expanded <= 256L * 1024 * 1024) { "Expanded JAR exceeds its bound." }
                val count = zip.read(buffer)
                if (count < 0) break
                expanded += count
            } while (true)
        }
    } }
    val providerName = requireNotNull(provider)
    require(names == index && providerName.replace('.', '/') + ".class" in names)
    val manifest = requireNotNull(attributes)
    require(manifest.getValue("Souz-Plugin-Api") == PluginApi.ABI && manifest.getValue("Souz-Plugin-Jvm") == "21")
    require(manifest.getValue("Class-Path") == null)
    return PluginBinding(requireNotNull(manifest.getValue("Souz-Plugin-Id")), requireNotNull(manifest.getValue("Souz-Plugin-Version")),
        Files.newInputStream(path).use { copyArtifact(it, OutputStream.nullOutputStream()) }).also(::requireIdentity)
}

internal fun copyArtifact(input: InputStream, output: OutputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    var length = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        length += count
        require(length <= PLUGIN_JAR_BYTES)
        digest.update(buffer, 0, count)
        output.write(buffer, 0, count)
    }
    require(length > 0)
    return java.util.HexFormat.of().formatHex(digest.digest())
}
