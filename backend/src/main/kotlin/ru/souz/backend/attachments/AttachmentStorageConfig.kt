package ru.souz.backend.attachments

import java.net.URI
import java.net.URISyntaxException
import ru.souz.backend.config.BackendConfigSource

class AttachmentStorageConfig(
    val endpoint: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val region: String = "us-east-1",
    val pathStyleAccess: Boolean = true,
    val maxFileBytes: Int = 20_000_000,
    val maxConcurrentUploads: Int = 16,
) {
    init {
        val uri = try { URI(endpoint) } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Invalid attachment S3 endpoint.")
        }
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/")) {
            "Attachment S3 endpoint must be an HTTP(S) origin without credentials."
        }
        require(region.isNotBlank() && accessKey.isNotBlank() && secretKey.isNotBlank()) {
            "Attachment S3 region and credentials are required."
        }
        require(bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) && !bucket.contains("..")) {
            "Invalid attachment bucket name."
        }
        require(maxFileBytes in 1..100_000_000) { "Attachment file limit must be between 1 and 100000000 bytes." }
        require(maxConcurrentUploads in 1..1_000) { "Attachment transfer concurrency must be between 1 and 1000." }
    }

    override fun toString(): String = "AttachmentStorageConfig(configured=true)"

    companion object {
        fun load(source: BackendConfigSource): AttachmentStorageConfig? {
            fun value(key: String) = source.value("SOUZ_ATTACHMENTS_$key", "souz.attachments.${key.lowercase()}")
                ?.trim()?.takeIf(String::isNotEmpty)
            fun required(key: String) = requireNotNull(value(key)) { "Attachment $key is required." }
            fun <T : Any> parsed(key: String, default: T, parse: (String) -> T?): T =
                value(key)?.let { requireNotNull(parse(it)) { "Invalid attachment $key." } } ?: default
            return AttachmentStorageConfig(
                endpoint = value("S3_ENDPOINT") ?: return null,
                bucket = required("S3_BUCKET"),
                accessKey = required("S3_ACCESS_KEY"),
                secretKey = required("S3_SECRET_KEY"),
                region = value("S3_REGION") ?: "us-east-1",
                pathStyleAccess = parsed("S3_PATH_STYLE", true, String::toBooleanStrictOrNull),
                maxFileBytes = parsed("MAX_FILE_BYTES", 20_000_000, String::toIntOrNull),
                maxConcurrentUploads = parsed("MAX_CONCURRENT_UPLOADS", 16, String::toIntOrNull),
            )
        }
    }
}
