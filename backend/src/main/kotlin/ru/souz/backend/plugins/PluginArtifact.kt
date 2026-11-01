package ru.souz.backend.plugins

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
import ru.souz.plugins.api.SkillPluginApi

internal data class PluginArtifact(val id: String, val version: String, val digest: String) {
    companion object {
        const val MAX_BYTES = 64 * 1024 * 1024
        val ID = Regex("[a-z][a-z0-9_]{0,39}")

        /** Validates bytes without executing plugin code; also bounds expanded archive size. */
        fun inspect(bytes: ByteArray): PluginArtifact {
            require(bytes.size in 1..MAX_BYTES) { "Plugin artifact must be at most $MAX_BYTES bytes." }
            return ZipInputStream(ByteArrayInputStream(bytes)).use { jar ->
                var first = jar.nextEntry
                if (first?.name == "META-INF/") first = jar.nextEntry
                require(first?.name == "META-INF/MANIFEST.MF") { "Missing plugin manifest." }
                // JarInputStream parses the manifest eagerly, before an expanded-size limit can apply.
                val manifest = jar.readNBytes(65_537)
                require(manifest.size <= 65_536) { "Plugin manifest is too large." }
                val attributes = Manifest(ByteArrayInputStream(manifest)).mainAttributes
                fun attribute(name: String) = requireNotNull(attributes.getValue(name)) { "Missing $name." }
                val id = attribute("Souz-Plugin-Id")
                val version = attribute("Souz-Plugin-Version")
                require(ID.matches(id)) { "Invalid plugin ID." }
                require(version.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))) { "Invalid plugin version." }
                require(attribute("Souz-Plugin-Api") == SkillPluginApi.ABI) { "Incompatible plugin ABI." }
                require(attribute("Souz-Plugin-Jvm") == "21") { "Plugin must target JVM 21." }
                require(attributes.getValue("Class-Path") == null) { "External JAR Class-Path is forbidden." }
                var count = 1
                var expanded = manifest.size.toLong()
                val buffer = ByteArray(8192)
                val names = mutableSetOf("META-INF/MANIFEST.MF")
                while (true) {
                    val entry = jar.nextEntry ?: break
                    require(++count <= 20_000 && names.add(entry.name)) { "Too many or duplicate JAR entries." }
                    val classPath = entry.name.replace(MULTI_RELEASE_PREFIX, "")
                    require(!entry.name.endsWith(".class") || !PluginClassLoader.isSharedResource(classPath)) {
                        "Shared SDK dependencies must be compile-only: ${entry.name}"
                    }
                    val header = jar.readNBytes(8)
                    expanded += header.size
                    require(expanded <= 256L * 1024 * 1024) { "Expanded plugin artifact is too large." }
                    if (entry.name.endsWith(".class")) {
                        require(header.size == 8 && header.take(4) == listOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte())) {
                            "Malformed plugin class."
                        }
                        val major = (header[6].toInt() and 255) * 256 + (header[7].toInt() and 255)
                        require(major <= 65) { "Plugin contains classes requiring a newer JVM." }
                    }
                    var read = jar.read(buffer)
                    while (read >= 0) {
                        expanded += read
                        require(expanded <= 256L * 1024 * 1024) { "Expanded plugin artifact is too large." }
                        read = jar.read(buffer)
                    }
                }
                PluginArtifact(id, version, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }
        }

        private val MULTI_RELEASE_PREFIX = Regex("^META-INF/versions/[0-9]+/")
    }
}
