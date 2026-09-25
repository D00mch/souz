package ru.souz.backend.hooks

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.backend.storage.postgres.postgresStorageMapper
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxScope

internal data class HookRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

internal data class VerifiedHookEvent(val payload: String, val eventId: String)

/** Runs directly in the hook owner's configured sandbox, without activating a Skill or LLM. */
internal class HookVerifier(private val config: HookConfig, private val sandboxes: RuntimeSandboxFactory) {
    private val slots = Semaphore(config.concurrentVerifiers)
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun verify(hook: LoadedHook, request: HookRequest): VerifiedHookEvent {
        if (!slots.tryAcquire()) throw hookError(429, "hook_verifier_busy")
        try {
            val verify = requireNotNull(hook.definition.verify)
            val sandbox = withContext(Dispatchers.IO) { sandboxes.create(SandboxScope(userId = hook.definition.ownerUserId)) }
            val input = postgresStorageMapper.writeValueAsString(mapOf(
                "version" to 1, "method" to request.method, "path" to request.path,
                "headers" to request.headers, "bodyBase64" to Base64.getEncoder().encodeToString(request.body),
                "parameters" to verify.parameters, "checkedAt" to Instant.now().toString(),
            ))
            val workspace = requireNotNull(sandbox.runtimePaths.workspaceRootPath)
            val fs = sandbox.fileSystem
            val directory = fs.resolvePath("$workspace/.hook-verifier-${UUID.randomUUID()}")
            val output = try {
                withContext(Dispatchers.IO) {
                    fs.createDirectory(directory)
                    for ((relative, bytes) in hook.files) {
                        val file = fs.resolvePath("${directory.path}/$relative")
                        fs.createDirectory(fs.resolvePath(requireNotNull(file.parentPath)))
                        fs.writeBytes(file, bytes)
                    }
                }
                sandbox.commandExecutor.execute(SandboxCommandRequest(
                    runtime = SandboxCommandRuntime.PYTHON,
                    scriptPath = "${directory.path}/${verify.script}",
                    workingDirectory = directory.path,
                    stdin = input,
                    timeoutMillis = config.verifierTimeoutMillis,
                ))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    val current = fs.resolvePath(directory.path)
                    if (current.exists) fs.delete(current, recursively = true)
                }
            }
            check(!output.timedOut && output.exitCode == 0) { "Verifier command failed" }
            require(output.stdout.toByteArray().size <= HookDefinitions.MAX_BODY_BYTES)
            val result = parseHookJson(output.stdout.toByteArray())
            require(result.isObject && result.fieldNames().asSequence().all { it in setOf("accept", "eventId", "payload") })
            require(result["accept"]?.isBoolean == true)
            if (!result["accept"].booleanValue()) throw hookError(401, "hook_verification_rejected")
            val eventId = result["eventId"]?.takeIf(JsonNode::isTextual)?.textValue()
            require(eventId != null && validHookEventId(eventId))
            val payload = if (result.has("payload")) result["payload"].toString() else {
                decodeUtf8(request.body).also { parseHookJson(it.toByteArray()) }
            }
            require(payload.toByteArray().size <= HookDefinitions.MAX_BODY_BYTES)
            return VerifiedHookEvent(payload, eventId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: ru.souz.backend.http.BackendV1Exception) {
            throw rejected
        } catch (error: Exception) {
            log.warn("Hook {} verifier failed ({})", hook.definition.hookId, error.javaClass.simpleName)
            throw hookError(503, "hook_verifier_failed")
        } finally { slots.release() }
    }
}

internal fun validHookEventId(key: String): Boolean = key.isNotBlank() && key.length <= 200 && key.none(Char::isISOControl)

internal fun parseHookJson(bytes: ByteArray): JsonNode = postgresStorageMapper.factory.createParser(decodeUtf8(bytes)).use { parser ->
    parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    requireNotNull(postgresStorageMapper.readTree<JsonNode>(parser)).also { require(parser.nextToken() == null) }
}
