package ru.souz.backend.plugins

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile
import ru.souz.plugins.api.PluginApi

/** Immutable archive identity; inspection never constructs or links plugin classes. */
internal data class PluginArtifact(val id: String, val version: String, val digest: String) {
    val binding: PluginBinding get() = PluginBinding(id, version, digest)

    companion object {
        const val MAX_BYTES = 64 * 1024 * 1024
        const val PROVIDER_RESOURCE = "META-INF/services/ru.souz.plugins.api.SouzPlugin"
        val ID = Regex("[a-z][a-z0-9_]{0,39}")
        private val VERSION = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
        private val MULTI_RELEASE_PREFIX = Regex("^META-INF/versions/[0-9]+/")

        fun inspect(path: Path): PluginArtifact {
            require(Files.size(path) in 1..MAX_BYTES.toLong()) { "Plugin archive exceeds the size limit." }
            val digest = artifactDigest(path)
            // ZipInputStream alone accepts a truncated central directory; the loader would reject
            // those accepted bytes later. Validate both views without resolving any plugin class.
            val indexedNames = ZipFile(path.toFile()).use { zip ->
                val names = mutableSetOf<String>()
                for (entry in zip.entries()) require(names.size < 20_000 && names.add(entry.name)) {
                    "Too many or duplicate JAR entries."
                }
                names
            }
            return Files.newInputStream(path).use { stream -> ZipInputStream(stream).use { jar ->
                var first = jar.nextEntry
                val names = mutableSetOf<String>()
                if (first?.name == "META-INF/") { names += "META-INF/"; first = jar.nextEntry }
                require(first?.name == "META-INF/MANIFEST.MF") { "Missing plugin manifest." }
                val manifestBytes = jar.readNBytes(65_537)
                require(manifestBytes.size <= 65_536) { "Plugin manifest exceeds the size limit." }
                val manifest = Manifest(manifestBytes.inputStream()).mainAttributes
                fun attribute(name: String) = requireNotNull(manifest.getValue(name)) { "Missing $name." }
                val id = attribute("Souz-Plugin-Id")
                val version = attribute("Souz-Plugin-Version")
                require(ID.matches(id) && VERSION.matches(version)) { "Invalid plugin identity." }
                require(attribute("Souz-Plugin-Api") == PluginApi.ABI) { "Incompatible plugin ABI." }
                require(attribute("Souz-Plugin-Jvm") == "21") { "Plugin must target JVM 21." }
                require(manifest.getValue("Class-Path") == null) { "External JAR Class-Path is forbidden." }
                var expanded = manifestBytes.size.toLong()
                names += "META-INF/MANIFEST.MF"
                var provider: String? = null
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = jar.nextEntry ?: break
                    require(names.size < 20_000 && names.add(entry.name)) { "Too many or duplicate JAR entries." }
                    val classPath = entry.name.replace(MULTI_RELEASE_PREFIX, "")
                    require(!entry.name.endsWith(".class") || !PluginClassLoader.isSharedResource(classPath)) {
                        "Shared SDK/runtime classes must be compile-only."
                    }
                    val header = jar.readNBytes(if (entry.name == PROVIDER_RESOURCE) 8193 else 8)
                    expanded += header.size
                    if (entry.name == PROVIDER_RESOURCE) {
                        require(header.size <= 8192) { "Plugin SPI descriptor is too large." }
                        val providers = header.toString(Charsets.UTF_8).lineSequence()
                            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
                        require(providers.size == 1 && providers.single().matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]*"))) {
                            "Expected exactly one plugin SPI provider."
                        }
                        provider = providers.single()
                    }
                    if (entry.name.endsWith(".class")) {
                        require(header.size == 8 && header.take(4) == listOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte())) {
                            "Malformed plugin class."
                        }
                        val major = (header[6].toInt() and 255) * 256 + (header[7].toInt() and 255)
                        require(major <= 65) { "Plugin requires a newer JVM." }
                    }
                    require(expanded <= 256L * 1024 * 1024) { "Expanded plugin archive exceeds the size limit." }
                    var read = jar.read(buffer)
                    while (read >= 0) {
                        expanded += read
                        require(expanded <= 256L * 1024 * 1024) { "Expanded plugin archive exceeds the size limit." }
                        read = jar.read(buffer)
                    }
                }
                require(provider != null && "${provider.replace('.', '/')}.class" in names) { "Plugin SPI provider is missing from the archive." }
                require(names == indexedNames) { "Inconsistent JAR archive index." }
                PluginArtifact(id, version, digest)
            } }
        }
    }
}

internal fun artifactDigest(path: Path): String {
    val hash = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8192)
        var total = 0L
        var count = input.read(buffer)
        while (count >= 0) {
            total += count
            require(total <= PluginArtifact.MAX_BYTES) { "Plugin archive exceeds the size limit." }
            hash.update(buffer, 0, count)
            count = input.read(buffer)
        }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
