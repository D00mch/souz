package ru.souz.backend.plugins

import java.net.URI
import java.util.UUID
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
    val uploadMillis: Long = 120_000,
    val userFailureThreshold: Int = 5,
    val versionFailureThreshold: Int = 10,
    val versionMinimumUsers: Int = 3,
    val versionWindowMillis: Long = 60_000,
    val cooldownMillis: Long = 30_000,
    val severeIncidentThreshold: Int = 3,
) {
    init {
        require(listOf(pollMillis, queueMillis, callMillis, leaseMillis, renewMillis, prepareMillis,
            retryMillis, cleanupMillis, graceMillis, shutdownMillis, uploadMillis, versionWindowMillis,
            cooldownMillis).all { it > 0 }) { "Plugin durations must be positive." }
        require(jitterMillis in 0 until Long.MAX_VALUE && pollMillis <= Long.MAX_VALUE - jitterMillis && renewMillis < leaseMillis)
        require(userFailureThreshold > 0 && severeIncidentThreshold > 0 && versionMinimumUsers in 1..versionFailureThreshold)
    }

    companion object {
        fun load(source: BackendConfigSource): PluginRuntimeConfig {
            val defaults = PluginRuntimeConfig()
            fun duration(key: String, default: Long) = source.plugin(key)?.let {
                requireNotNull(it.toLongOrNull()) { "Invalid plugin $key." }
            } ?: default
            fun count(key: String, default: Int) = Math.toIntExact(duration(key, default.toLong()))
            return PluginRuntimeConfig(
                pollMillis = duration("POLL_MILLIS", defaults.pollMillis), jitterMillis = duration("JITTER_MILLIS", defaults.jitterMillis),
                queueMillis = duration("QUEUE_MILLIS", defaults.queueMillis), callMillis = duration("CALL_MILLIS", defaults.callMillis),
                leaseMillis = duration("LEASE_MILLIS", defaults.leaseMillis), renewMillis = duration("RENEW_MILLIS", defaults.renewMillis),
                prepareMillis = duration("PREPARE_MILLIS", defaults.prepareMillis), retryMillis = duration("RETRY_MILLIS", defaults.retryMillis),
                cleanupMillis = duration("CLEANUP_MILLIS", defaults.cleanupMillis), graceMillis = duration("GRACE_MILLIS", defaults.graceMillis),
                shutdownMillis = duration("SHUTDOWN_MILLIS", defaults.shutdownMillis), uploadMillis = duration("UPLOAD_MILLIS", defaults.uploadMillis),
                userFailureThreshold = count("USER_FAILURE_THRESHOLD", defaults.userFailureThreshold),
                versionFailureThreshold = count("VERSION_FAILURE_THRESHOLD", defaults.versionFailureThreshold),
                versionMinimumUsers = count("VERSION_MINIMUM_USERS", defaults.versionMinimumUsers),
                versionWindowMillis = duration("VERSION_WINDOW_MILLIS", defaults.versionWindowMillis),
                cooldownMillis = duration("COOLDOWN_MILLIS", defaults.cooldownMillis),
                severeIncidentThreshold = count("SEVERE_INCIDENT_THRESHOLD", defaults.severeIncidentThreshold),
            )
        }
    }
}

class S3PluginArtifactConfig(
    val endpoint: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val region: String = "us-east-1",
    val pathStyleAccess: Boolean = true,
) {
    init {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        require(uri != null && (uri.scheme == "http" || uri.scheme == "https") && uri.host != null &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) { "Plugin S3 endpoint must be an HTTP(S) origin." }
        require(bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) && ".." !in bucket) { "Invalid plugin bucket." }
        require(listOf(accessKey, secretKey, region).none(String::isBlank)) { "Plugin storage credentials and region are required." }
    }

    companion object {
        fun load(source: BackendConfigSource): S3PluginArtifactConfig? {
            val endpoint = source.plugin("S3_ENDPOINT") ?: return null
            fun required(key: String) = requireNotNull(source.plugin(key)) { "Plugin $key is required." }
            return S3PluginArtifactConfig(endpoint, required("S3_BUCKET"), required("S3_ACCESS_KEY"), required("S3_SECRET_KEY"),
                source.plugin("S3_REGION") ?: "us-east-1", source.plugin("S3_PATH_STYLE")?.toBooleanStrict() ?: true)
        }
    }
}

private fun BackendConfigSource.plugin(key: String): String? =
    value("SOUZ_PLUGINS_$key", "souz.plugins." + key.lowercase())?.trim()?.takeIf(String::isNotEmpty)

data class PluginBinding(val id: String, val version: String, val digest: String)
internal data class PluginVersion(val binding: PluginBinding, val retired: Boolean, val quarantined: Boolean, val severeIncidents: Int)
internal data class PluginRegistration(
    val id: String, val defaultDigest: String?, val enabled: Boolean, val revision: Long, val versions: List<PluginVersion>,
)
internal data class PluginCallLease(val ownerId: String, val binding: PluginBinding, val token: UUID = UUID.randomUUID())
internal enum class PluginCallOutcome { SUCCESS, TECHNICAL_FAILURE, IGNORED }
internal class PluginUnavailableException(val code: String) : IllegalStateException(code)
internal class PluginRegistryException(val code: String) : IllegalArgumentException(code)
