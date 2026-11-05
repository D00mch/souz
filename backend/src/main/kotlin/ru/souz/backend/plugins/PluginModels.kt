package ru.souz.backend.plugins

import java.util.UUID

data class PluginBinding(val id: String, val version: String, val digest: String)
internal data class PluginVersion(val binding: PluginBinding, val retired: Boolean, val quarantined: Boolean, val severeIncidents: Int)
internal data class PluginRegistration(
    val id: String, val defaultDigest: String?, val enabled: Boolean, val revision: Long, val versions: List<PluginVersion>,
)
internal data class PluginCallLease(val ownerId: String, val binding: PluginBinding, val token: UUID = UUID.randomUUID())
internal enum class PluginCallOutcome { SUCCESS, TECHNICAL_FAILURE, IGNORED }
internal class PluginUnavailableException(val code: String) : IllegalStateException(code)
internal class PluginRegistryException(val code: String) : IllegalArgumentException(code)

/** Artifact identity validation has no storage or runtime dependency. */
internal fun requireIdentity(binding: PluginBinding) {
    if (!binding.id.matches(Regex("[a-z][a-z0-9_]{0,39}")) ||
        !binding.version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) ||
        !binding.digest.matches(Regex("[a-f0-9]{64}"))) {
        throw PluginRegistryException("plugin_invalid_identity")
    }
}
