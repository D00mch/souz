package ru.souz.local

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class LocalModelStore(
    val rootDir: Path = defaultRootDir(),
) {
    private val l = LoggerFactory.getLogger(LocalModelStore::class.java)
    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val downloadMutex = Mutex()

    fun modelPath(profile: LocalModelProfile): Path =
        rootDir.resolve(profile.id).resolve(profile.ggufFilename)

    fun isPresent(profile: LocalModelProfile): Boolean = Files.isRegularFile(modelPath(profile))

    fun canDownload(profile: LocalModelProfile): Boolean = profile.downloadUrl.isNotBlank()

    fun requireAvailable(profile: LocalModelProfile): Path {
        val target = modelPath(profile)
        if (Files.isRegularFile(target)) {
            return target
        }
        error("Model ${profile.displayName} is not downloaded yet. Download it before starting local inference.")
    }

    suspend fun download(
        profile: LocalModelProfile,
        onProgress: suspend (LocalModelDownloadProgress) -> Unit = {},
    ): Path = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val target = modelPath(profile)
            if (Files.isRegularFile(target)) {
                onProgress(LocalModelDownloadProgress(bytesDownloaded = Files.size(target), totalBytes = Files.size(target)))
                return@withContext target
            }

            if (!canDownload(profile)) {
                error("Model ${profile.id} is not available locally and has no download URL configured.")
            }

            Files.createDirectories(target.parent)
            val tempFile = target.resolveSibling("${profile.ggufFilename}.part")
            val token = System.getenv("HF_TOKEN")
                ?: System.getenv("HUGGING_FACE_HUB_TOKEN")

            val requestBuilder = HttpRequest.newBuilder(URI.create(profile.downloadUrl))
                .GET()
                .header("User-Agent", "Souz Local Inference")
            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            l.info("Downloading local model {} into {}", profile.huggingFaceRepoId, target)
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                response.body().use(InputStream::close)
                error("Failed to download ${profile.ggufFilename}: HTTP ${response.statusCode()}")
            }

            val totalBytes = response.headers()
                .firstValue("Content-Length")
                .orElse(null)
                ?.toLongOrNull()

            runCatching { Files.deleteIfExists(tempFile) }
            try {
                onProgress(LocalModelDownloadProgress(bytesDownloaded = 0, totalBytes = totalBytes))
                response.body().use { input ->
                    Files.newOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloadedBytes = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
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
                    }
                }
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                l.info("Downloaded local model {} to {}", profile.id, target)
                target
            } catch (error: Throwable) {
                runCatching { Files.deleteIfExists(tempFile) }
                throw error
            }
        }
    }

    companion object {
        private fun defaultRootDir(): Path = Path.of(
            System.getProperty("user.home"),
            ".local",
            "state",
            "souz",
            "models",
        )
    }
}

data class LocalModelDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes
            ?.takeIf { it > 0L }
            ?.let { total -> (bytesDownloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}
