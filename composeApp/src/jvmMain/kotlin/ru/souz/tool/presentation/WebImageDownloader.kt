package ru.souz.tool.presentation

import ru.souz.tool.BadInputException
import ru.souz.tool.files.FilesToolUtil
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val IMAGE_DOWNLOAD_CONNECT_TIMEOUT_MS = 5_000L
private const val IMAGE_DOWNLOAD_REQUEST_TIMEOUT_MS = 10_000L

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
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofMillis(IMAGE_DOWNLOAD_CONNECT_TIMEOUT_MS))
        .build()

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
        val uri = toSafeUri(imageUrl)
        val tempFile = Files.createTempFile("souz_img_", ".bin").toFile()
        try {
            val request = HttpRequest.newBuilder(uri)
                .GET()
                .header("User-Agent", DEFAULT_WEB_USER_AGENT)
                .timeout(Duration.ofMillis(IMAGE_DOWNLOAD_REQUEST_TIMEOUT_MS))
                .build()
            val response = sendWithTimeout(
                request = request,
                imageUrl = imageUrl,
                bodyHandler = HttpResponse.BodyHandlers.ofFile(tempFile.toPath()),
            )
            if (response.statusCode() >= 400) {
                throw BadInputException("Image download failed: HTTP ${response.statusCode()} for $imageUrl")
            }
            val contentType = response.headers().firstValue("Content-Type").orElse("")
            val extension = detectDownloadedImageExtension(
                file = tempFile,
                contentType = contentType,
                sourceUrl = imageUrl,
            ) ?: throw BadInputException("Downloaded asset is not an image: $imageUrl")
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

    private fun toSafeUri(url: String): URI = URI.create(url.replace(" ", "%20"))

    private fun <T> sendWithTimeout(
        request: HttpRequest,
        imageUrl: String,
        bodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        return runCatching {
            client.sendAsync(request, bodyHandler)
                .orTimeout(IMAGE_DOWNLOAD_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .join()
        }.getOrElse { error ->
            val cause = (error as? CompletionException)?.cause ?: error
            if (cause is HttpTimeoutException || cause is TimeoutException) {
                throw BadInputException(
                    "Image download timed out after ${IMAGE_DOWNLOAD_REQUEST_TIMEOUT_MS}ms for $imageUrl",
                )
            }
            throw cause
        }
    }

    private fun detectDownloadedImageExtension(
        file: File,
        contentType: String,
        sourceUrl: String,
    ): String? {
        val header = file.inputStream().use { input ->
            ByteArray(64).also { bytes ->
                val read = input.read(bytes)
                if (read in 0 until bytes.size) {
                    for (i in read until bytes.size) bytes[i] = 0
                }
            }
        }
        val normalizedContentType = contentType.substringBefore(';').trim().lowercase()
        if (normalizedContentType.contains("pdf")) return null

        sniffImageExtension(header)?.let { return it }

        val byMime = when (normalizedContentType) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/webp" -> "webp"
            "image/svg+xml" -> null
            "image/avif" -> "avif"
            else -> null
        }
        if (byMime != null) return byMime

        val urlExtension = extensionFromUrl(sourceUrl)
        return urlExtension.takeIf { it in supportedImageExtensions }
    }

    private fun sniffImageExtension(header: ByteArray): String? {
        if (header.size >= 4 &&
            header[0] == 0x25.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x44.toByte() &&
            header[3] == 0x46.toByte()
        ) {
            return null
        }
        if (header.size >= 8 &&
            header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() &&
            header[3] == 0x47.toByte()
        ) {
            return "png"
        }
        if (header.size >= 3 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        ) {
            return "jpg"
        }
        val headerText = header.toString(StandardCharsets.UTF_8).trimStart()
        if (headerText.startsWith("<svg", ignoreCase = true) || headerText.contains("<svg", ignoreCase = true)) {
            return null
        }
        if (header.size >= 6) {
            val signature = String(header.copyOfRange(0, 6), StandardCharsets.US_ASCII)
            if (signature == "GIF87a" || signature == "GIF89a") return "gif"
        }
        if (header.size >= 2 &&
            header[0] == 0x42.toByte() &&
            header[1] == 0x4D.toByte()
        ) {
            return "bmp"
        }
        if (header.size >= 12) {
            val riff = String(header.copyOfRange(0, 4), StandardCharsets.US_ASCII)
            val webp = String(header.copyOfRange(8, 12), StandardCharsets.US_ASCII)
            if (riff == "RIFF" && webp == "WEBP") return "webp"
            val ftyp = String(header.copyOfRange(4, 12), StandardCharsets.US_ASCII)
            if (ftyp.contains("avif")) return "avif"
        }
        return null
    }

    private fun extensionFromUrl(url: String): String {
        return runCatching {
            URI.create(url.replace(" ", "%20")).path.substringAfterLast('.', "").lowercase()
        }.getOrDefault("")
    }

    companion object {
        private const val DEFAULT_WEB_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
        private val supportedImageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "avif")
    }
}
