package ru.souz.backend.hooks

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxScope

data class HookConfig(
    val owners: Set<String> = emptySet(),
    val queuePerHook: Int = 20,
    val eventsPerUserPerDay: Int = 100,
    val llmCallsPerEvent: Int = 20,
    val llmCallsPerUserPerDay: Int = 200,
    val concurrentExecutions: Int = 4,
    val executionTimeoutMillis: Long = 300_000,
    val verifierTimeoutMillis: Long = 5_000,
    val concurrentVerifiers: Int = 2,
) {
    init {
        require(owners.all { it.isNotBlank() && it.length <= 200 })
        require(listOf(queuePerHook, eventsPerUserPerDay, llmCallsPerEvent, llmCallsPerUserPerDay, concurrentExecutions).all { it > 0 })
        require(executionTimeoutMillis > 0)
        require(verifierTimeoutMillis in 100..30_000 && concurrentVerifiers in 1..16)
    }
}

internal data class HookAuth(val type: String, val tokenSha256: String)
internal data class HookVerify(
    val runtime: String,
    val script: String,
    val parameters: JsonNode = JsonNodeFactory.instance.objectNode(),
)

internal data class HookDefinition(
    val version: Int,
    val hookId: String,
    val ownerUserId: String,
    val enabled: Boolean = true,
    val auth: HookAuth? = null,
    val verify: HookVerify? = null,
    val prompt: String,
) {
    fun accepts(authorization: String?): Boolean {
        val auth = auth ?: return false
        val parts = authorization?.split(' ', limit = 2) ?: return false
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return false
        val token = parts[1]
        return token.length in 32..512 && token.none(Char::isWhitespace) && MessageDigest.isEqual(
            auth.tokenSha256.lowercase().toByteArray(), sha256(token.toByteArray()).toByteArray(),
        )
    }

    val revision: String get() = sha256("$version\n$hookId\n$ownerUserId\n$prompt".toByteArray())
}

/** Bytes are captured on reload and never reread while handling a public request. */
internal class LoadedHook(val definition: HookDefinition, val files: Map<String, ByteArray> = emptyMap()) {
    val revision: String = if (definition.verify == null) definition.revision else sha256(buildString {
        append(definition.revision).append('\n').append(definition.verify)
        files.toSortedMap().forEach { (path, bytes) -> append('\n').append(path).append(':').append(sha256(bytes)) }
    }.toByteArray())
}

/** Only the host's explicit owner allowlist supplies workspace identities. */
internal class HookDefinitions(private val sandboxes: RuntimeSandboxFactory, private val config: HookConfig) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val yaml = com.fasterxml.jackson.databind.ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    suspend fun load(owner: String): List<LoadedHook> = withContext(Dispatchers.IO) {
        require(owner in config.owners)
        val sandbox = sandboxes.create(SandboxScope(userId = owner))
        val fs = sandbox.fileSystem
        val workspace = sandbox.runtimePaths.workspaceRootPath ?: return@withContext emptyList()
        val root = fs.resolvePath("$workspace/hooks")
        if (!root.exists) return@withContext emptyList()
        require(!root.isSymbolicLink && root.isDirectory && fs.isPathSafe(root)) { "Unsafe hooks directory." }
        val files = fs.listDescendants(root, maxDepth = 1).filter { it.isDirectory && !it.isSymbolicLink }
        require(files.all { it.parentPath == root.path }) { "Hook directory escapes root." }
        require(files.size <= 100) { "Too many hook directories." }
        files.mapNotNull { directory ->
            val path = fs.resolvePath("${directory.path}/hook.yaml")
            if (!path.exists) return@mapNotNull null
            // Canonical LOCAL paths must remain below the same directory; DOCKER rejects symlinks itself.
            require(!path.isSymbolicLink && path.isRegularFile && path.parentPath == directory.path && fs.isPathSafe(path))
            val bytes = fs.openInputStream(path).use { it.readNBytes(MAX_BODY_BYTES + 1) }
            require(bytes.size <= MAX_BODY_BYTES) { "Hook definition is too large." }
            val definition = yaml.readValue<HookDefinition>(decodeUtf8(bytes))
            require(definition.version == 1 && ID.matches(definition.hookId)) { "Invalid hook identity/version." }
            require((definition.auth == null) != (definition.verify == null)) { "Choose exactly one hook auth mode." }
            definition.auth?.let { require(it.type == "bearer" && DIGEST.matches(it.tokenSha256)) { "Invalid hook auth." } }
            require(definition.prompt.isNotBlank() && definition.prompt.length <= 16_384) { "Invalid hook prompt." }
            require(definition.ownerUserId in config.owners) { "Hook owner is not enabled by the host." }
            if (sandbox.mode != SandboxMode.LOCAL) require(definition.ownerUserId == owner) { "Hook owner differs from workspace owner." }
            if (definition.ownerUserId != owner) return@mapNotNull null
            val snapshot = definition.verify?.let { verify ->
                require(verify.runtime == "PYTHON" && safeHookRelativePath(verify.script) && verify.script.endsWith(".py"))
                require(verify.parameters.isObject && verify.parameters.toString().toByteArray().size <= 8192)
                snapshotHookFiles(fs, directory).also { require(verify.script in it) { "Missing verifier script." } }
            }.orEmpty()
            LoadedHook(definition, snapshot)
        }
    }

    suspend fun loadSafely(owner: String): List<LoadedHook> = try {
        load(owner)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // Parser exceptions can include secrets or prompt fragments; do not log their messages.
        log.warn("Hooks disabled for owner {}: invalid or unavailable workspace ({})", owner, error.javaClass.simpleName)
        emptyList()
    }

    companion object {
        const val MAX_BODY_BYTES = 65_536
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val DIGEST = Regex("[a-fA-F0-9]{64}")
    }
}

internal fun safeHookRelativePath(path: String): Boolean = path.length in 1..240 &&
    path.split('/').let { parts -> parts.size <= 8 && parts.all { it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_.-]+")) } }

private fun snapshotHookFiles(fs: SandboxFileSystem, root: SandboxPathInfo): Map<String, ByteArray> {
    val entries = fs.listDescendants(root, maxDepth = 8, includeHidden = true)
    require(entries.size <= 64) { "Too many verifier files." }
    var total = 0
    return buildMap {
        for (entry in entries) {
            require(!entry.isSymbolicLink && fs.isPathSafe(entry) && entry.path.startsWith("${root.path}/"))
            val relative = entry.path.removePrefix("${root.path}/")
            require(safeHookRelativePath(relative)) { "Unsafe verifier path." }
            if (entry.isDirectory) continue
            require(entry.isRegularFile) { "Verifier file is not regular." }
            if (relative == "hook.yaml") continue
            val bytes = fs.openInputStream(entry).use { it.readNBytes(1_048_576 - total + 1) }
            total += bytes.size
            require(total <= 1_048_576) { "Verifier files exceed 1 MiB." }
            put(relative, bytes)
        }
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()
