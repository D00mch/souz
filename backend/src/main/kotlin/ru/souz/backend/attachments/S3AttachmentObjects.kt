package ru.souz.backend.attachments

import java.net.URI
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import ru.souz.backend.crypto.sha256Hex
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

class S3AttachmentObjects(config: AttachmentStorageConfig) : AutoCloseable {
    private val bucket = config.bucket
    private val client = S3Client.builder()
        .endpointOverride(URI(config.endpoint))
        .region(Region.of(config.region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey)))
        .forcePathStyle(config.pathStyleAccess)
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10))
            .socketTimeout(Duration.ofSeconds(120)))
        .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(120)).apiCallAttemptTimeout(Duration.ofSeconds(120)) }
        .build()

    suspend fun put(file: StoredAttachment, bytes: ByteArray): Unit = runInterruptible(Dispatchers.IO) {
        client.putObject({ it.bucket(bucket).key(file.objectKey).contentType(file.mimeType) },
            RequestBody.fromContentProvider({ bytes.inputStream() }, bytes.size.toLong(), file.mimeType))
    }

    suspend fun read(file: StoredAttachment): ByteArray = runInterruptible(Dispatchers.IO) {
        client.getObject { it.bucket(bucket).key(file.objectKey) }.use { response ->
            if (response.response().contentLength() != file.sizeBytes) throw AttachmentException("attachment_corrupt")
            // Bound the response by stored metadata, independently of today's upload limit.
            response.readNBytes(Math.toIntExact(file.sizeBytes) + 1).also {
                if (it.size.toLong() != file.sizeBytes || it.sha256Hex() != file.sha256) {
                    throw AttachmentException("attachment_corrupt")
                }
            }
        }
    }

    internal suspend fun readUpload(objectKey: String, limit: Int): ByteArray = runInterruptible(Dispatchers.IO) {
        client.getObject { it.bucket(bucket).key(objectKey) }.use { response ->
            if (response.response().contentLength() > limit) throw AttachmentException("attachment_too_large")
            response.readAttachmentBytes(limit).also {
                if (it.size.toLong() != response.response().contentLength()) throw AttachmentException("attachment_corrupt")
            }
        }
    }

    suspend fun delete(file: StoredAttachment): Unit = runInterruptible(Dispatchers.IO) {
        client.deleteObject { it.bucket(bucket).key(file.objectKey) }
    }

    override fun close() = client.close()
}
