package ru.souz.backend.plugins

import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.slf4j.LoggerFactory
import ru.souz.backend.http.BackendV1Exception

internal data class PluginAdminEntry(val desired: PluginRegistration, val applied: PluginStatus?)
internal data class PluginAdminStatus(val replicaId: String, val plugins: List<PluginAdminEntry>, val registryError: String?)
internal data class PluginMutationAccepted(val id: String, val version: String?, val digest: String?, val revision: Long, val enabled: Boolean)

/** Authentication and durable publication are independent from each replica's polling lifecycle. */
internal class PluginAdministration(
    private val repository: PostgresPluginRepository,
    private val artifacts: S3PluginArtifacts,
    private val runtime: BackendPluginRuntime,
    private val token: String,
    val config: PluginRuntimeConfig,
    val replicaId: String,
) {
    fun authenticate(authorization: String?) {
        val supplied = authorization?.takeIf { it.substringBefore(' ').equals("Bearer", ignoreCase = true) }
            ?.substringAfter(' ', "")?.toByteArray() ?: byteArrayOf()
        if (!MessageDigest.isEqual(token.toByteArray(), supplied)) {
            throw pluginHttpError(401, "plugin_admin_unauthorized", "Operator authentication is required.")
        }
    }

    suspend fun install(path: Path, expectedRevision: Long): PluginMutationAccepted = operation {
        val binding = try { runInterruptible(Dispatchers.IO) { PluginArtifact.inspect(path).binding } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { throw pluginHttpError(400, "plugin_invalid_artifact", "Invalid or incompatible plugin JAR.") }
        catch (_: IOException) { throw pluginHttpError(400, "plugin_invalid_artifact", "Malformed plugin JAR.") }
        artifacts.store(path, binding)
        repository.install(binding, expectedRevision).accepted()
    }

    suspend fun status(id: String? = null): PluginAdminStatus = operation {
        val desired = repository.registry().filter { id == null || it.id == id }
        if (id != null && desired.isEmpty()) throw pluginHttpError(404, "plugin_not_found", "Plugin is not installed.")
        val snapshot = runtime.snapshot
        PluginAdminStatus(replicaId, desired.map { row -> PluginAdminEntry(row, snapshot.status.firstOrNull { it.id == row.id }) }, snapshot.registryError)
    }

    suspend fun select(id: String, version: String, revision: Long) = operation { repository.select(id, version, revision).accepted() }
    suspend fun disable(id: String, revision: Long) = operation { repository.disable(id, revision).accepted() }
    suspend fun retire(id: String, version: String, revision: Long) = operation { repository.retire(id, version, revision).accepted() }
    suspend fun reenable(id: String, version: String, revision: Long) = operation { repository.reenable(id, version, revision).accepted() }

    private suspend fun <T> operation(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (known: BackendV1Exception) { throw known }
    catch (known: PluginRegistryException) {
        val status = when {
            "conflict" in known.code || "quarantin" in known.code -> 409
            "not_found" in known.code || "missing" in known.code -> 404
            else -> 400
        }
        throw pluginHttpError(status, known.code, "Plugin registry operation was rejected.")
    } catch (_: IllegalArgumentException) {
        throw pluginHttpError(400, "plugin_invalid_request", "Invalid plugin request.")
    } catch (failure: Exception) {
        val diagnostic = UUID.randomUUID().toString()
        logger.warn("Plugin administration failed diagnostic={} exceptionClass={}", diagnostic, failure.javaClass.name)
        throw pluginHttpError(503, "plugin_storage_unavailable", "Plugin storage is unavailable; diagnostic: $diagnostic")
    }

    private fun PluginRegistration.accepted() = PluginMutationAccepted(
        id, versions.firstOrNull { it.binding.digest == defaultDigest }?.binding?.version, defaultDigest, revision, enabled,
    )

    private companion object { val logger = LoggerFactory.getLogger(PluginAdministration::class.java) }
}

internal fun pluginHttpError(status: Int, code: String, message: String) = BackendV1Exception(HttpStatusCode.fromValue(status), code, message)
