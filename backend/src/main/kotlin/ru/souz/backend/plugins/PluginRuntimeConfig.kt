package ru.souz.backend.plugins

import java.net.URI
import ru.souz.backend.config.BackendConfigSource

data class PluginRuntimeConfig(
    val pollMillis: Long = 60_000,
    val jitterMillis: Long = 15_000,
    val queueMillis: Long = 60_000,
    val callMillis: Long = 60_000,
    val leaseMillis: Long = 90_000,
    val renewMillis: Long = 10_000,
    val prepareMillis: Long = 30_000,
    val retryMillis: Long = 30_000,
    val cleanupMillis: Long = 10_000,
    val graceMillis: Long = 30_000,
    val shutdownMillis: Long = 30_000,
    val userFailureThreshold: Int = 5,
    val versionFailureThreshold: Int = 10,
    val versionWindowMillis: Long = 60_000,
    val versionMinimumUsers: Int = 3,
    val cooldownMillis: Long = 30_000,
    val severeIncidentThreshold: Int = 3,
    val uploadMillis: Long = 120_000,
) {
    init {
        require(listOf(pollMillis, queueMillis, callMillis, leaseMillis, renewMillis, prepareMillis,
            retryMillis, cleanupMillis, graceMillis, shutdownMillis, versionWindowMillis, cooldownMillis,
            uploadMillis).all { it > 0 }) { "Plugin deadlines must be positive." }
        require(jitterMillis in 0 until Long.MAX_VALUE && pollMillis <= Long.MAX_VALUE - jitterMillis) {
            "Invalid plugin polling jitter."
        }
        require(renewMillis < leaseMillis) { "Plugin renewal must precede lease expiry." }
        require(listOf(userFailureThreshold, versionFailureThreshold, versionMinimumUsers, severeIncidentThreshold)
            .all { it > 0 } && versionMinimumUsers <= versionFailureThreshold) { "Invalid plugin breaker thresholds." }
    }

    companion object {
        fun load(source: BackendConfigSource): PluginRuntimeConfig {
            fun long(key: String, default: Long): Long = pluginConfigValue(source, key)?.let {
                requireNotNull(it.toLongOrNull()) { "Invalid plugin $key." }
            } ?: default
            fun int(key: String, default: Int): Int = pluginConfigValue(source, key)?.let {
                requireNotNull(it.toIntOrNull()) { "Invalid plugin $key." }
            } ?: default
            return PluginRuntimeConfig(
                pollMillis = long("POLL_MILLIS", 60_000), jitterMillis = long("JITTER_MILLIS", 15_000),
                queueMillis = long("QUEUE_MILLIS", 60_000), callMillis = long("CALL_MILLIS", 60_000),
                leaseMillis = long("LEASE_MILLIS", 90_000), renewMillis = long("RENEW_MILLIS", 10_000),
                prepareMillis = long("PREPARE_MILLIS", 30_000), retryMillis = long("RETRY_MILLIS", 30_000),
                cleanupMillis = long("CLEANUP_MILLIS", 10_000), graceMillis = long("GRACE_MILLIS", 30_000),
                shutdownMillis = long("SHUTDOWN_MILLIS", 30_000),
                userFailureThreshold = int("USER_FAILURE_THRESHOLD", 5),
                versionFailureThreshold = int("VERSION_FAILURE_THRESHOLD", 10),
                versionWindowMillis = long("VERSION_WINDOW_MILLIS", 60_000),
                versionMinimumUsers = int("VERSION_MINIMUM_USERS", 3),
                cooldownMillis = long("COOLDOWN_MILLIS", 30_000),
                severeIncidentThreshold = int("SEVERE_INCIDENT_THRESHOLD", 3),
                uploadMillis = long("UPLOAD_MILLIS", 120_000),
            )
        }
    }
}

/** Artifact credentials are separate from expiring user-file storage and never appear in diagnostics. */
class S3PluginArtifactConfig(
    val endpoint: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val region: String = "us-east-1",
    val pathStyleAccess: Boolean = true,
) {
    init {
        val origin = runCatching { URI(endpoint) }.getOrNull()
        require(origin != null && origin.scheme in listOf("https", "http") && origin.host != null &&
            origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null &&
            origin.rawPath.orEmpty() in listOf("", "/")) { "Plugin S3 endpoint must be an HTTP(S) origin." }
        require(bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) && ".." !in bucket) {
            "Invalid plugin S3 bucket."
        }
        require(listOf(accessKey, secretKey, region).none(String::isBlank)) { "Plugin S3 credentials and region are required." }
    }

    override fun toString(): String = "S3PluginArtifactConfig(configured=true)"

    companion object {
        fun load(source: BackendConfigSource): S3PluginArtifactConfig? {
            val endpoint = pluginConfigValue(source, "S3_ENDPOINT") ?: return null
            fun required(key: String) = requireNotNull(pluginConfigValue(source, key)) { "Plugin $key is required." }
            val pathStyle = pluginConfigValue(source, "S3_PATH_STYLE")?.let {
                requireNotNull(it.toBooleanStrictOrNull()) { "Invalid plugin S3_PATH_STYLE." }
            } ?: true
            return S3PluginArtifactConfig(endpoint, required("S3_BUCKET"), required("S3_ACCESS_KEY"),
                required("S3_SECRET_KEY"), pluginConfigValue(source, "S3_REGION") ?: "us-east-1", pathStyle)
        }
    }
}

private fun pluginConfigValue(source: BackendConfigSource, key: String): String? =
    source.value("SOUZ_PLUGINS_$key", "souz.plugins.${key.lowercase()}")?.trim()?.takeIf(String::isNotEmpty)
