package ru.souz.backend.plugins

import java.util.UUID

data class PluginBinding(val id: String, val version: String, val digest: String)

/**
 * Registry state for one installed plugin version.
 *
 * @property binding Plugin ID, version, and SHA-256 digest of the installed artifact.
 * @property retired Retirement blocks new assignments. Existing pins can still use an eligible version.
 * @property quarantined Quarantine blocks new invocations, including those from pinned executions.
 * @property severeIncidents Persisted count used to trigger quarantine. Re-enabling resets it.
 */
internal data class PluginVersion(
    val binding: PluginBinding,
    val retired: Boolean,
    val quarantined: Boolean,
    val severeIncidents: Int
)

/**
 * Durable desired registry state, reconciled independently by each backend replica.
 *
 * @property id Stable plugin ID shared by all its versions.
 * @property defaultDigest SHA-256 digest of the desired default JAR, or `null` if none is selected.
 * @property enabled Plugin-wide admission switch. Disabling blocks new calls across all versions
 * and lets admitted calls finish.
 * @property revision Optimistic concurrency revision that advances on admin mutations and new severe incidents.
 * @property versions All registered versions, including retired and quarantined entries.
 */
internal data class PluginRegistration(
    val id: String,
    val defaultDigest: String?,
    val enabled: Boolean,
    val revision: Long,
    val versions: List<PluginVersion>,
)

/**
 * Renewable lease that serializes calls for one owner and plugin across replicas and versions.
 *
 * @property ownerId ID of the user who owns the invocation.
 * @property binding Exact plugin artifact selected for the invocation.
 * @property token Unique fencing token used to verify that the invocation still holds the lease.
 */
internal data class PluginCallLease(
    val ownerId: String,
    val binding: PluginBinding,
    val token: UUID = UUID.randomUUID()
)

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
