package ru.souz.tool.web.internal

import org.apache.tika.Tika
import kotlinx.coroutines.runBlocking
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.BadInputException
import ru.souz.tool.files.FilesToolUtil
import java.net.URI
import java.util.UUID

private const val IMAGE_DOWNLOAD_REQUEST_TIMEOUT_MS = 10_000L
private const val IMAGE_DOWNLOAD_MAX_BYTES = 20 * 1024 * 1024

/**
 * Shared image downloader/validator used by:
 * - [ToolWebImageSearch] for optional local image export
 * - [ToolPresentationCreate] for remote `imagePath` URLs
 *
 * The downloader stores only assets that are detected as supported raster image formats.
 */
class WebImageDownloader(
    private val filesToolUtil: FilesToolUtil,
) {
    private val downloadBinary: suspend (String, Long) -> WebBinaryResponse = { url, timeoutMillis ->
        WebHttpSupport().downloadBinary(url, timeoutMillis)
    }
    private val tika = Tika()
    private val webToolSupport = WebToolSupport()

    fun downloadToDirectory(
        imageUrl: String,
        preferredName: String,
        outputDir: String?,
        meta: ToolInvocationMeta = ToolInvocationMeta.Empty,
    ): String {
        val dir = resolveImageOutputDir(outputDir, meta)
        val safeName = preferredName
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .trim('_')
            .ifBlank { "image" }
            .take(80)
        val downloaded = downloadAndDetectExtension(imageUrl)
        val candidate = uniquePath(
            basePath = "${dir.path}/$safeName.${downloaded.extension}",
            meta = meta,
        )
        filesToolUtil.writeBytes(candidate, downloaded.body, meta)
        return candidate.path
    }

    fun downloadToTemp(
        imageUrl: String,
        meta: ToolInvocationMeta = ToolInvocationMeta.Empty,
    ): String? {
        return runCatching {
            val downloaded = downloadAndDetectExtension(imageUrl)
            val sandboxPath = uniquePath(
                basePath = "${filesToolUtil.runtimeSandbox(meta).runtimePaths.stateRootPath}/web-images/${UUID.randomUUID()}.${downloaded.extension}",
                meta = meta,
            )
            filesToolUtil.writeBytes(sandboxPath, downloaded.body, meta)
            sandboxPath.path
        }.getOrNull()
    }

    private data class DownloadedTemp(
        val body: ByteArray,
        val extension: String,
    )

    private fun downloadAndDetectExtension(imageUrl: String): DownloadedTemp {
        val normalizedUrl = webToolSupport.requireHttpUrl(imageUrl)
        try {
            val response = runBlocking {
                downloadBinary(normalizedUrl, IMAGE_DOWNLOAD_REQUEST_TIMEOUT_MS)
            }
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
            val contentType = response.firstHeader("Content-Type").orEmpty()
            val extension = detectDownloadedImageExtension(
                body = response.body,
                contentType = contentType,
                sourceUrl = normalizedUrl,
            ) ?: throw BadInputException("Downloaded asset is not a supported raster image: $normalizedUrl")
            return DownloadedTemp(body = response.body, extension = extension)
        } catch (e: Exception) {
            throw e
        }
    }

    private fun uniquePath(basePath: String, meta: ToolInvocationMeta): ru.souz.runtime.sandbox.SandboxPathInfo {
        val base = filesToolUtil.resolvePath(basePath, meta)
        if (!base.exists) return base
        val parentPath = base.parentPath ?: error("Image output path must include a parent directory: ${base.path}")
        val stem = base.name.substringBeforeLast('.', base.name)
        val ext = base.name.substringAfterLast('.', "")
        for (idx in 1..500) {
            val suffix = if (ext.isBlank()) "${stem}_$idx" else "${stem}_$idx.$ext"
            val candidate = filesToolUtil.resolvePath("$parentPath/$suffix", meta)
            if (!candidate.exists) return candidate
        }
        val fallbackName = if (ext.isBlank()) {
            "${stem}_${System.currentTimeMillis()}"
        } else {
            "${stem}_${System.currentTimeMillis()}.$ext"
        }
        return filesToolUtil.resolvePath("$parentPath/$fallbackName", meta)
    }

    private fun resolveImageOutputDir(
        outputDir: String?,
        meta: ToolInvocationMeta,
    ): ru.souz.runtime.sandbox.SandboxPathInfo {
        val raw = outputDir?.trim().takeUnless { it.isNullOrBlank() }
            ?: "${filesToolUtil.resolveSouzWebAssetsDirectory(meta).path}/"
        val resolved = filesToolUtil.resolvePath(raw, meta)
        val dir = if (resolved.isDirectory || raw.endsWith("/") || raw.endsWith("\\")) {
            resolved
        } else {
            resolved.parentPath?.let { filesToolUtil.resolvePath(it, meta) } ?: resolved
        }
        if (!filesToolUtil.isPathSafe(dir, meta)) {
            throw BadInputException("Access denied: File path must be within the home directory")
        }
        filesToolUtil.createDirectory(dir, meta)
        return dir
    }

    private fun detectDownloadedImageExtension(
        body: ByteArray,
        contentType: String,
        sourceUrl: String,
    ): String? {
        val declaredMime = normalizeMime(contentType)
        val detectedMime = runCatching { normalizeMime(tika.detect(body)) }.getOrNull()
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
            URI.create(webToolSupport.toSafeHttpUrl(url)).path.substringAfterLast('.', "").lowercase()
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
