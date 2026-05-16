package ru.souz.llms.runtime

import java.nio.file.Files
import java.nio.file.Path

object ImageFileFormats {
    private val extensionToMimeType = linkedMapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
    )

    private val mimeTypeToExtensions: Map<String, Set<String>> = extensionToMimeType.entries
        .groupBy(keySelector = { it.value }, valueTransform = { it.key })
        .mapValues { (_, extensions) -> extensions.toSet() }

    fun detectMimeType(path: Path): String? {
        val detected = runCatching { Files.probeContentType(path) }.getOrNull()
        if (!detected.isNullOrBlank() && detected.startsWith("image/")) {
            return detected
        }
        return mimeTypeForExtension(path.fileName.toString().substringAfterLast('.', ""))
    }

    fun mimeTypeForExtension(extension: String): String? =
        extensionToMimeType[extension.lowercase()]

    fun primaryExtensionForMimeType(mimeType: String): String =
        supportedExtensionsForMimeType(mimeType).firstOrNull() ?: "bin"

    fun supportedExtensionsForMimeType(mimeType: String): Set<String> =
        mimeTypeToExtensions[mimeType.lowercase()] ?: setOf("bin")
}
