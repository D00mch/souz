package ru.souz.tool.web

import org.apache.tika.Tika
import ru.souz.tool.BadInputException
import ru.souz.tool.files.FilesToolUtil
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val IMAGE_DOWNLOAD_REQUEST_TIMEOUT_MS = 10_000L
private const val IMAGE_DOWNLOAD_MAX_BYTES = 20 * 1024 * 1024

/**
 * Shared image downloader/validator used by:
 * - [ToolWebImageSearch] for optional local image export
 * - [ToolPresentationCreate] for remote `imagePath` URLs
 *
 * The downloader stores only assets that are detected as supported raster image formats.
 */
class WebImageDownloader internal constructor(
    private val filesToolUtil: FilesToolUtil,
    private val downloadBinary: (String, Long) -> WebBinaryResponse = ::webDownloadBinary,
    private val tika: Tika = Tika(),
) {
    fun downloadToDirectory(
        imageUrl: String,
        preferredName: String,
        outputDir: String?,
    ): String {
        val dir = resolveImageOutputDir(outputDir)
        val safeName = preferredName
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .trim('_')
            .ifBlank { "image" }
            .take(80)
        val downloaded = downloadAndDetectExtension(imageUrl)
        val candidate = uniqueFile(File(dir, "$safeName.${downloaded.extension}"))
        Files.move(downloaded.file.toPath(), candidate.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return candidate.absolutePath
    }

    fun downloadToTemp(imageUrl: String): String? {
        return runCatching {
            val downloaded = downloadAndDetectExtension(imageUrl)
            val renamed = File(downloaded.file.parentFile, "${downloaded.file.nameWithoutExtension}.${downloaded.extension}")
            try {
                Files.move(downloaded.file.toPath(), renamed.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                if (downloaded.file.exists()) downloaded.file.delete()
                throw e
            }
            renamed.absolutePath
        }.getOrNull()
    }

    private data class DownloadedTemp(
        val file: File,
        val extension: String,
    )

    private fun downloadAndDetectExtension(imageUrl: String): DownloadedTemp {
        val normalizedUrl = requireHttpUrl(imageUrl)
        val tempFile = Files.createTempFile("souz_img_", ".bin").toFile()
        try {
            val response = downloadBinary(normalizedUrl, IMAGE_DOWNLOAD_REQUEST_TIMEOUT_MS)
            if (response.statusCode >= 400) {
                throw BadInputException("Image download failed: HTTP ${response.statusCode} for $normalizedUrl")
            }
            val declaredLength = response.firstHeader("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength > IMAGE_DOWNLOAD_MAX_BYTES) {
                throw BadInputException("Image download failed: asset is larger than ${IMAGE_DOWNLOAD_MAX_BYTES / (1024 * 1024)}MB")
            }
            if (response.body.size > IMAGE_DOWNLOAD_MAX_BYTES) {
                throw BadInputException("Image download failed: asset is larger than ${IMAGE_DOWNLOAD_MAX_BYTES / (1024 * 1024)}MB")
            }
            Files.write(tempFile.toPath(), response.body)
            val contentType = response.firstHeader("Content-Type").orEmpty()
            val extension = detectDownloadedImageExtension(
                file = tempFile,
                contentType = contentType,
                sourceUrl = normalizedUrl,
            ) ?: throw BadInputException("Downloaded asset is not a supported raster image: $normalizedUrl")
            return DownloadedTemp(file = tempFile, extension = extension)
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    private fun uniqueFile(base: File): File {
        if (!base.exists()) return base
        val parent = base.parentFile
        val stem = base.nameWithoutExtension
        val ext = base.extension
        for (idx in 1..500) {
            val candidate = File(parent, "${stem}_$idx.$ext")
            if (!candidate.exists()) return candidate
        }
        return File(parent, "${stem}_${System.currentTimeMillis()}.$ext")
    }

    private fun resolveImageOutputDir(outputDir: String?): File {
        val raw = outputDir?.trim().takeUnless { it.isNullOrBlank() }
            ?: FilesToolUtil.souzWebAssetsDirectoryPath.toString() + File.separator
        val resolved = File(filesToolUtil.applyDefaultEnvs(raw))
        val dir = if (resolved.isDirectory || raw.endsWith("/") || raw.endsWith("\\")) {
            resolved
        } else {
            resolved.parentFile ?: resolved
        }
        dir.mkdirs()
        filesToolUtil.requirePathIsSave(dir)
        return dir
    }

    private fun detectDownloadedImageExtension(
        file: File,
        contentType: String,
        sourceUrl: String,
    ): String? {
        val declaredMime = normalizeMime(contentType)
        val detectedMime = runCatching { normalizeMime(tika.detect(file)) }.getOrNull()
        if (declaredMime in blockedImageMimeTypes || detectedMime in blockedImageMimeTypes) return null
        mimeToExtension[detectedMime]?.let { return it }
        mimeToExtension[declaredMime]?.let { return it }
        val explicitNonImageMime = listOf(declaredMime, detectedMime).any { mime ->
            mime != null && mime !in genericFallbackMimeTypes && !mime.startsWith("image/")
        }
        if (explicitNonImageMime) return null
        val urlExtension = extensionFromUrl(sourceUrl)
        return urlExtension.takeIf { it in supportedImageExtensions }
    }

    private fun extensionFromUrl(url: String): String {
        return runCatching {
            URI.create(toSafeHttpUrl(url)).path.substringAfterLast('.', "").lowercase()
        }.getOrDefault("")
    }

    private fun normalizeMime(raw: String?): String? {
        val normalized = raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return normalized.takeIf { it.isNotBlank() }
    }

    companion object {
        private val supportedImageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "avif")
        private val blockedImageMimeTypes = setOf("application/pdf", "image/svg+xml")
        private val genericFallbackMimeTypes = setOf("application/octet-stream")
        private val mimeToExtension = mapOf(
            "image/jpeg" to "jpg",
            "image/jpg" to "jpg",
            "image/png" to "png",
            "image/gif" to "gif",
            "image/bmp" to "bmp",
            "image/webp" to "webp",
            "image/avif" to "avif",
        )
    }
}
