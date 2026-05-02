package ru.souz.llms.local

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.paths.DefaultSouzPaths

class LocalModelStore(
    val rootDir: Path = defaultRootDir(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    private val l = LoggerFactory.getLogger(LocalModelStore::class.java)
    private val downloadMutex = Mutex()

    fun modelPath(profile: LocalDownloadableProfile): Path =
        rootDir.resolve(profile.id).resolve(profile.ggufFilename)

    fun isPresent(profile: LocalDownloadableProfile): Boolean = Files.isRegularFile(modelPath(profile))

    fun canDownload(profile: LocalDownloadableProfile): Boolean = profile.downloadUrl.isNotBlank()

    fun requireAvailable(profile: LocalDownloadableProfile): Path {
        val target = modelPath(profile)
        if (Files.isRegularFile(target)) {
            return target
        }
        error("Model ${profile.displayName} is not downloaded yet. Download it before starting local inference.")
    }

    suspend fun downloadRequiredAssets(
        profile: LocalModelProfile,
        onProgress: suspend (LocalModelDownloadProgress) -> Unit = {},
    ): Path = downloadMutex.withLock {
        val missingProfiles = profile.requiredDownloadProfiles().filterNot(::isPresent)

        if (missingProfiles.isEmpty()) {
            return@withLock requireAvailable(profile)
        }

        missingProfiles.forEachIndexed { index, missingProfile ->
            downloadInternal(missingProfile) { progress ->
                onProgress(
                    progress.copy(
                        activeProfileName = missingProfile.displayName,
                        completedProfiles = index,
                        totalProfiles = missingProfiles.size,
                    )
                )
            }
            val target = modelPath(missingProfile)
            onProgress(
                LocalModelDownloadProgress(
                    bytesDownloaded = Files.size(target),
                    totalBytes = Files.size(target),
                    activeProfileName = missingProfile.displayName,
                    completedProfiles = index + 1,
                    totalProfiles = missingProfiles.size,
                )
            )
        }

        requireAvailable(profile)
    }

    suspend fun download(
        profile: LocalDownloadableProfile,
        onProgress: suspend (LocalModelDownloadProgress) -> Unit = {},
    ): Path = downloadMutex.withLock {
        downloadInternal(profile, onProgress)
    }

    private suspend fun downloadInternal(
        profile: LocalDownloadableProfile,
        onProgress: suspend (LocalModelDownloadProgress) -> Unit = {},
    ): Path {
        return withContext(Dispatchers.IO) {
            val target = modelPath(profile)
            if (Files.isRegularFile(target)) {
                onProgress(
                    LocalModelDownloadProgress(
                        bytesDownloaded = Files.size(target),
                        totalBytes = Files.size(target),
                    )
                )
                return@withContext target
            }

            if (!canDownload(profile)) {
                error("Model ${profile.id} is not available locally and has no download URL configured.")
            }

            Files.createDirectories(target.parent)
            val tempFile = target.resolveSibling("${profile.ggufFilename}.part")
            val token = System.getenv("HF_TOKEN")
                ?: System.getenv("HUGGING_FACE_HUB_TOKEN")
            var existingBytes = tempFile.takeIf(Files::isRegularFile)?.let(Files::size) ?: 0L
            var response = sendDownloadRequest(
                downloadUrl = profile.downloadUrl,
                token = token,
                resumeFromBytes = existingBytes,
            )

            if (existingBytes > 0L && response.statusCode() == HTTP_RANGE_NOT_SATISFIABLE) {
                response.body().use(InputStream::close)
                val totalBytes = totalBytes(
                    response = response,
                    existingBytes = existingBytes,
                )
                if (totalBytes != null && existingBytes == totalBytes) {
                    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    onProgress(LocalModelDownloadProgress(bytesDownloaded = totalBytes, totalBytes = totalBytes))
                    return@withContext target
                }

                runCatching { Files.deleteIfExists(tempFile) }
                existingBytes = 0L
                response = sendDownloadRequest(
                    downloadUrl = profile.downloadUrl,
                    token = token,
                    resumeFromBytes = 0L,
                )
            }

            if (existingBytes > 0L && response.statusCode() == HTTP_OK) {
                response.body().use(InputStream::close)
                runCatching { Files.deleteIfExists(tempFile) }
                existingBytes = 0L
                response = sendDownloadRequest(
                    downloadUrl = profile.downloadUrl,
                    token = token,
                    resumeFromBytes = 0L,
                )
            }

            if (response.statusCode() !in HTTP_OK..HTTP_PARTIAL_CONTENT) {
                response.body().use(InputStream::close)
                error("Failed to download ${profile.ggufFilename}: HTTP ${response.statusCode()}")
            }

            val totalBytes = totalBytes(
                response = response,
                existingBytes = existingBytes,
            )
            val outputOptions = if (existingBytes > 0L && response.statusCode() == HTTP_PARTIAL_CONTENT) {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
            } else {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            }

            l.info(
                "Downloading local model {} into {}{}",
                profile.huggingFaceRepoId,
                target,
                if (existingBytes > 0L) " (resuming at $existingBytes bytes)" else "",
            )
            try {
                onProgress(LocalModelDownloadProgress(bytesDownloaded = existingBytes, totalBytes = totalBytes))
                val coroutineContext = currentCoroutineContext()
                response.body().use { input ->
                    Files.newOutputStream(tempFile, *outputOptions).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloadedBytes = existingBytes
                        while (coroutineContext.isActive) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            onProgress(
                                LocalModelDownloadProgress(
                                    bytesDownloaded = downloadedBytes,
                                    totalBytes = totalBytes,
                                )
                            )
                        }
                        coroutineContext.ensureActive()
                    }
                }
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                l.info("Downloaded local model {} to {}", profile.id, target)
                target
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    l.info("Download of local model {} was cancelled, keeping partial file {}", profile.id, tempFile)
                } else {
                    l.warn("Download of local model {} failed, keeping partial file {}: {}", profile.id, tempFile, error.message)
                }
                throw error
            }
        }
    }

    private fun sendDownloadRequest(
        downloadUrl: String,
        token: String?,
        resumeFromBytes: Long,
    ): HttpResponse<InputStream> {
        val requestBuilder = HttpRequest.newBuilder(URI.create(downloadUrl))
            .GET()
            .header("User-Agent", "Souz Local Inference")
        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }
        if (resumeFromBytes > 0L) {
            requestBuilder.header("Range", "bytes=$resumeFromBytes-")
        }
        return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun totalBytes(
        response: HttpResponse<*>,
        existingBytes: Long,
    ): Long? = parseTotalBytes(response.headers().firstValue("Content-Range").orElse(null))
        ?: response.headers()
            .firstValue("Content-Length")
            .orElse(null)
            ?.toLongOrNull()
            ?.let { contentLength ->
                if (response.statusCode() == HTTP_PARTIAL_CONTENT && existingBytes > 0L) {
                    existingBytes + contentLength
                } else {
                    contentLength
                }
            }

    private fun parseTotalBytes(contentRange: String?): Long? {
        val normalized = contentRange?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        val separatorIndex = normalized.lastIndexOf('/')
        if (separatorIndex < 0 || separatorIndex == normalized.lastIndex) return null
        val totalPart = normalized.substring(separatorIndex + 1)
        return totalPart.toLongOrNull()
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        private fun defaultRootDir(): Path = DefaultSouzPaths().modelsDir
    }
}

data class LocalModelDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val activeProfileName: String? = null,
    val completedProfiles: Int = 0,
    val totalProfiles: Int = 1,
) {
    val fraction: Float?
        get() = totalBytes
            ?.takeIf { it > 0L }
            ?.let { total -> (bytesDownloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}
