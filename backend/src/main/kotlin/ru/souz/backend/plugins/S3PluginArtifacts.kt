package ru.souz.backend.plugins

import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception

/** Immutable objects have separate credentials and no expiry or deletion API. */
internal class S3PluginArtifacts(config: S3PluginArtifactConfig) : AutoCloseable {
    private val bucket = config.bucket
    private val client = S3Client.builder().endpointOverride(URI(config.endpoint)).region(Region.of(config.region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey)))
        .forcePathStyle(config.pathStyleAccess)
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(30)))
        .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(30)) }.build()

    suspend fun store(path: Path, binding: PluginBinding): Unit = runInterruptible(Dispatchers.IO) {
        requireIdentity(binding)
        require(Files.newInputStream(path).use { copyArtifact(it, OutputStream.nullOutputStream()) } == binding.digest)
        try {
            client.putObject({ it.bucket(bucket).key(key(binding)).ifNoneMatch("*").contentType("application/java-archive") }, RequestBody.fromFile(path))
        } catch (failure: S3Exception) {
            if (failure.statusCode() != 409 && failure.statusCode() != 412) throw failure
            read(binding, OutputStream.nullOutputStream())
        }
    }
    suspend fun download(binding: PluginBinding, target: Path): Unit = runInterruptible(Dispatchers.IO) {
        requireIdentity(binding)
        val temporary = Files.createTempFile(target.toAbsolutePath().parent, "download-", ".part")
        try {
            Files.newOutputStream(temporary).use { read(binding, it) }
            require(inspectPlugin(temporary) == binding) { "Artifact identity differs from the pinned version." }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun read(binding: PluginBinding, output: OutputStream) {
        client.getObject { it.bucket(bucket).key(key(binding)) }.use {
            if (it.response().contentLength() !in 1..PLUGIN_JAR_BYTES.toLong()) throw IOException("Invalid artifact length.")
            if (copyArtifact(it, output) != binding.digest) throw IOException("Corrupt plugin artifact.")
        }
    }
    private fun key(binding: PluginBinding) = "plugins/" + binding.digest + ".jar"
    override fun close() = client.close()
}
