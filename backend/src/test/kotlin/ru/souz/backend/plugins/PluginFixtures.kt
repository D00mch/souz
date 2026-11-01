package ru.souz.backend.plugins

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream

/** Repackage the real compiled fixtures to exercise manifest/SPI failures without extra projects. */
internal fun repackagePlugin(
    bytes: ByteArray,
    attributes: Map<String, String> = emptyMap(),
    resources: Map<String, String> = emptyMap(),
): ByteArray = ByteArrayOutputStream().use { output ->
    JarInputStream(ByteArrayInputStream(bytes)).use { input ->
        val manifest = input.manifest
        attributes.forEach { (key, value) -> manifest.mainAttributes.putValue(key, value) }
        JarOutputStream(output, manifest).use { jar ->
            while (true) {
                val entry = input.nextJarEntry ?: break
                if (entry.name in resources) continue
                jar.putNextEntry(JarEntry(entry.name))
                input.copyTo(jar)
                jar.closeEntry()
            }
            resources.forEach { (name, content) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(content.toByteArray())
                jar.closeEntry()
            }
        }
    }
    output.toByteArray()
}
