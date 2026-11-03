package ru.souz.backend.plugins

import java.time.Instant
import java.util.UUID

data class PluginBinding(val id: String, val version: String, val digest: String)

data class PluginVersion(
    val binding: PluginBinding,
    val retired: Boolean,
    val quarantined: Boolean,
    val severeIncidents: Int,
)

data class PluginRegistration(
    val id: String,
    val defaultDigest: String?,
    val enabled: Boolean,
    val revision: Long,
    val versions: List<PluginVersion>,
)

data class PluginCallLease(
    val ownerId: String,
    val pluginId: String,
    val digest: String,
    val token: UUID,
    val expiresAt: Instant,
)

enum class PluginCallOutcome { SUCCESS, TECHNICAL_FAILURE, IGNORED }

/** Only bounded host codes cross the HTTP boundary; SQL/S3 exception text never does. */
class PluginRegistryException(val code: String) : IllegalArgumentException(code)

class PluginUnavailableException(val code: String) : IllegalStateException(code)
