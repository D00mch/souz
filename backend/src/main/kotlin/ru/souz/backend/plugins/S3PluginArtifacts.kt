package ru.souz.backend.plugins

import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.regions.Region

/** Immutable artifact objects; local files are disposable and object deletion is deliberately absent. */
class S3PluginArtifacts(config: S3PluginArtifactConfig) : AutoCloseable {
    private val bucket = config.bucket
    private val client = S3Client.builder().apply {
        endpointOverride(URI(config.endpoint))
        region(Region.of(config.region))
        credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey)))
        forcePathStyle(config.pathStyleAccess)
        requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10))
            .socketTimeout(Duration.ofSeconds(30)))
        overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(30)) }
    }.build()

    suspend fun store(path: Path, binding: PluginBinding): Unit = runInterruptible(Dispatchers.IO) {
        val digest = binding.digest.validDigest()
        Files.newInputStream(path).use { verify(it, OutputStream.nullOutputStream(), digest) }
        try {
            client.putObject({ it.bucket(bucket).key(key(digest)).contentType("application/java-archive").ifNoneMatch("*") },
                RequestBody.fromFile(path))
        } catch (failure: S3Exception) {
            if (failure.statusCode() != 409 && failure.statusCode() != 412) throw failure
            // A concurrent uploader may have won. Never overwrite, even if metadata looks correct.
            read(binding, OutputStream.nullOutputStream())
        }
    }

    suspend fun download(binding: PluginBinding, target: Path): Unit = runInterruptible(Dispatchers.IO) {
        val parent = requireNotNull(target.toAbsolutePath().parent)
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "plugin-download-", ".part")
        try {
            Files.newOutputStream(temporary).use { read(binding, it) }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun read(binding: PluginBinding, output: OutputStream) {
        val digest = binding.digest.validDigest()
        client.getObject { it.bucket(bucket).key(key(digest)) }.use { response ->
            val expected = response.response().contentLength()
            if (expected == null || expected !in 1..MAX_BYTES) throw PluginRegistryException("plugin_artifact_corrupt")
            if (verify(response, output, digest) != expected) throw PluginRegistryException("plugin_artifact_corrupt")
        }
    }

    private fun verify(input: InputStream, output: OutputStream, digest: String): Long {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var length = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            length += count
            if (length > MAX_BYTES) throw PluginRegistryException("plugin_artifact_too_large")
            hash.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        if (length == 0L || HexFormat.of().formatHex(hash.digest()) != digest) {
            throw PluginRegistryException("plugin_artifact_corrupt")
        }
        return length
    }

    override fun close() = client.close()

    private companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
        fun key(digest: String): String = "plugins/$digest.jar"
        fun String.validDigest(): String = also {
            if (!matches(Regex("[a-f0-9]{64}"))) throw PluginRegistryException("plugin_invalid_identity")
        }
    }
}
