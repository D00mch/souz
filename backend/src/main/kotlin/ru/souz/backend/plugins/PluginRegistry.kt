package ru.souz.backend.plugins

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.jar.JarFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.backend.common.asProperty
import ru.souz.backend.common.normalizeToolSchema
import ru.souz.plugins.PLUGIN_API_VERSION
import ru.souz.plugins.PluginTool
import ru.souz.plugins.SouzPlugin
import ru.souz.tool.ToolCategory

data class PluginBinding(val id: String, val version: String, val digest: String)
data class InstalledPlugin(val binding: PluginBinding, val tools: List<String>)
internal data class LoadedTool(val pluginId: String, val tool: PluginTool)

/** One lease owns all plugin generations used by a running execution and its children. */
internal class PluginLease(val tools: List<LoadedTool>, private val release: suspend () -> Unit) {
    private val mutex = Mutex()
    private var released = false

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            if (!released) {
                released = true
                release()
            }
        }
    }
}

class PluginRegistry(
    private val directory: Path? = null,
    private val cache: Path? = directory?.resolve(".cache"),
    private val reservedNames: Set<String> = emptySet(),
) {
    private class Generation(val binding: PluginBinding, val loader: URLClassLoader, val tools: List<LoadedTool>) {
        var leases = 0
    }

    private val logger = LoggerFactory.getLogger(PluginRegistry::class.java)
    private val mutex = Mutex()
    // Loading is serialized separately; state sections never perform filesystem or plugin IO.
    private val loading = Mutex()
    private val generations = mutableMapOf<String, Generation>()
    private val active = mutableMapOf<String, Generation>()
    private var poller: Job? = null
    private var initialized = false
    private var closed = false

    suspend fun start(scope: CoroutineScope) {
        initialize()
        mutex.withLock {
            checkOpen()
            if (directory != null && poller == null) poller = scope.launch {
                while (isActive) {
                    delay(5_000)
                    try { refresh() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { report("scan", e) }
                }
            }
        }
    }

    suspend fun installed(): List<InstalledPlugin> {
        initialize()
        return mutex.withLock {
            checkOpen()
            active.values.sortedBy { it.binding.id }.map { generation ->
                InstalledPlugin(generation.binding, generation.tools.map { it.tool.function.name })
            }
        }
    }

    suspend fun refresh() = loading.withLock {
        mutex.withLock { checkOpen() }
        scan()
    }

    internal suspend fun acquire(bindings: List<PluginBinding>): PluginLease {
        initialize()
        require(bindings.distinctBy { it.id }.size == bindings.size) { "Duplicate plugin binding" }
        bindings.forEach { require(it.digest.matches(Regex("[a-f0-9]{64}"))) { "Invalid plugin digest for ${it.id}" } }
        mutex.withLock { existingLease(bindings) }?.let { return it }
        return loading.withLock {
            mutex.withLock { existingLease(bindings) }?.let { return@withLock it }
            val staged = mutableListOf<Generation>()
            var acquired: PluginLease? = null
            val (present, retained) = mutex.withLock {
                checkOpen()
                val known = bindings.mapNotNull { binding -> generations[binding.digest]?.also {
                    require(it.binding == binding) { "Cached plugin binding differs from execution: ${binding.id}" }
                } }
                known.associateBy { it.binding.digest } to lease(known)
            }
            try {
                // Hold existing generations while recovering only the missing artifacts.
                val recovered = bindings.map { binding ->
                    present[binding.digest]?.let { return@map it }
                    val path = requireNotNull(cache) { "Plugin cache is not configured" }.resolve("${binding.digest}.jar")
                    val candidate = withContext(Dispatchers.IO) { load(path).also(staged::add) }
                    require(candidate.binding == binding) { "Cached plugin binding differs from execution: ${binding.id}" }
                    candidate
                }
                acquired = mutex.withLock {
                    checkOpen()
                    val selected = recovered.map { candidate ->
                        generations.getOrPut(candidate.binding.digest) { staged.remove(candidate); candidate }
                    }
                    lease(selected)
                }
                retained.close()
                closeGenerations(staged)
                staged.clear()
                currentCoroutineContext().ensureActive()
                requireNotNull(acquired)
            } catch (failure: Throwable) {
                acquired?.close()
                retained.close()
                discard(staged)
                throw failure
            }
        }
    }

    /** Called with the state mutex held; granting a lease must not suspend afterwards. */
    private fun existingLease(bindings: List<PluginBinding>): PluginLease? {
        checkOpen()
        val selected = bindings.map { binding ->
            val generation = generations[binding.digest] ?: return null
            require(generation.binding == binding) { "Cached plugin binding differs from execution: ${binding.id}" }
            generation
        }
        return lease(selected)
    }

    private fun lease(selected: List<Generation>): PluginLease {
        val tools = selected.flatMap { it.tools }
        require(tools.distinctBy { it.tool.function.name }.size == tools.size) { "Plugin tool collision in execution bindings" }
        selected.forEach { it.leases++ }
        return PluginLease(tools) {
            val retired = mutex.withLock {
                selected.forEach { it.leases-- }
                removeRetired()
            }
            closeGenerations(retired)
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        val job = mutex.withLock { poller.also { poller = null; closed = true } }
        job?.cancel()
        job?.join()
        loading.withLock {
            val retired = mutex.withLock { active.clear(); removeRetired() }
            closeGenerations(retired)
        }
    }

    private fun checkOpen() = check(!closed) { "Plugin registry is closed" }

    private suspend fun initialize() {
        if (mutex.withLock { checkOpen(); initialized }) return
        loading.withLock {
            if (!mutex.withLock { checkOpen(); initialized }) scan()
        }
    }

    private suspend fun scan() {
        val root = directory
        if (root != null) {
            // List first: an inaccessible installation directory must not uninstall plugins.
            val jars = withContext(Dispatchers.IO) {
                Files.list(root).use { files -> files.filter { it.fileName.toString().endsWith(".jar") }.sorted().toList() }
                    .also { Files.createDirectories(requireNotNull(cache)) }
            }
            for (jar in jars) {
                mutex.withLock { checkOpen() }
                val id = jar.fileName.toString().removeSuffix(".jar")
                val staged = mutableListOf<Generation>()
                try {
                    require(id.matches(PLUGIN_ID)) { "Invalid plugin filename: ${jar.fileName}" }
                    val artifact = withContext(Dispatchers.IO) { cacheJar(jar) }
                    val hash = artifact.fileName.toString().removeSuffix(".jar")
                    if (mutex.withLock { checkOpen(); active[id]?.binding?.digest == hash }) continue
                    val candidate = withContext(Dispatchers.IO) { load(artifact).also(staged::add) }
                    require(candidate.binding.id == id) { "Plugin filename $id differs from manifest ID ${candidate.binding.id}" }
                    mutex.withLock {
                        checkOpen()
                        val occupied = active.filterKeys { it != id }.values.flatMap { it.tools }.map { it.tool.function.name }.toSet()
                        val collision = candidate.tools.firstOrNull { it.tool.function.name in occupied }
                        require(collision == null) { "Plugin tool collision: ${collision?.tool?.function?.name}" }
                        active[id] = generations.getOrPut(hash) { staged.remove(candidate); candidate }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { report(id, e) }
                catch (e: LinkageError) { report(id, e) }
                finally { discard(staged) }
            }
            val retired = mutex.withLock {
                checkOpen()
                active.keys.retainAll(jars.map { it.fileName.toString().removeSuffix(".jar") }.toSet())
                removeRetired()
            }
            closeGenerations(retired)
        }
        mutex.withLock { checkOpen(); initialized = true }
    }

    private fun cacheJar(source: Path): Path {
        require(Files.isRegularFile(source) && !Files.isSymbolicLink(source)) { "Expected a regular plugin JAR" }
        val temporary = Files.createTempFile(requireNotNull(cache), "plugin-", ".part")
        try {
            Files.copy(source, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val target = cache.resolve("${digest(temporary)}.jar")
            if (!Files.exists(target)) Files.move(temporary, target, ATOMIC_MOVE)
            require(digest(target) == target.fileName.toString().removeSuffix(".jar")) { "Plugin cache is corrupt" }
            return target
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun load(path: Path): Generation {
        val hash = digest(path)
        require(path.fileName.toString() == "$hash.jar") { "Plugin cache is corrupt" }
        val (binding, entryPoint) = JarFile(path.toFile()).use { jar ->
            val manifest = requireNotNull(jar.manifest) { "Missing JAR manifest" }.mainAttributes
            val id = requireNotNull(manifest.getValue("Souz-Plugin-Id")) { "Missing Souz-Plugin-Id" }
            val version = requireNotNull(manifest.getValue("Souz-Plugin-Version")) { "Missing Souz-Plugin-Version" }
            require(id.matches(PLUGIN_ID)) { "Invalid plugin ID: $id" }
            require(version.isNotBlank() && version.length <= 128) { "Plugin version must be nonblank and at most 128 characters" }
            require(manifest.getValue("Souz-Plugin-Api") == PLUGIN_API_VERSION) { "Incompatible plugin SDK: expected $PLUGIN_API_VERSION, got ${manifest.getValue("Souz-Plugin-Api")}" }
            require(manifest.getValue("Class-Path").isNullOrBlank()) { "External JAR dependencies are unsupported" }
            require(jar.entries().asSequence().none { entry ->
                SHARED_PACKAGES.any(entry.name::startsWith)
            }) { "Plugin packages shared SDK/runtime classes" }
            PluginBinding(id, version, hash) to requireNotNull(manifest.getValue("Souz-Plugin-Class")) { "Missing Souz-Plugin-Class" }
        }
        val loader = URLClassLoader(arrayOf(path.toUri().toURL()), SouzPlugin::class.java.classLoader)
        try {
            val type = Class.forName(entryPoint, true, loader)
            require(type.classLoader === loader) { "Plugin entry point must belong to the JAR" }
            val plugin = type.getDeclaredConstructor().newInstance() as SouzPlugin
            val tools = plugin.tools.map { tool ->
                val fn = tool.function
                require(fn.name.matches(Regex("[a-z][a-z0-9_]*"))) { "Invalid local tool name: ${fn.name}" }
                require(fn.description.isNotBlank()) { "Tool ${fn.name} has no description" }
                require(ToolCategory.entries.any { it.name == tool.category }) { "Tool ${fn.name} has unknown category: ${tool.category}" }
                require(fn.parameters.type == "object") { "Tool ${fn.name} requires an object parameter schema" }
                val schema = normalizeToolSchema(fn.parameters.asProperty(), 0, "/tools/${fn.name}/parameters")
                val name = "${binding.id}__${fn.name}"
                require(name.length <= 64 && name !in reservedNames) { "Tool name is reserved or exceeds 64 characters: $name" }
                LoadedTool(binding.id, tool.copy(function = fn.copy(name = name, parameters = fn.parameters.copy(
                    properties = schema.properties.orEmpty(), required = schema.required.orEmpty(),
                ))))
            }
            require(tools.distinctBy { it.tool.function.name }.size == tools.size) { "Duplicate plugin tool" }
            return Generation(binding, loader, tools)
        } catch (failure: Throwable) {
            try { loader.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Detach under the state mutex, then close outside it. */
    private fun removeRetired(): List<Generation> {
        val retired = generations.values.filter { it.leases == 0 && active[it.binding.id] !== it }
        retired.forEach { generations.remove(it.binding.digest) }
        return retired
    }

    private suspend fun discard(staged: List<Generation>) = withContext(NonCancellable) {
        closeGenerations(staged + mutex.withLock { removeRetired() })
    }

    private suspend fun closeGenerations(retired: List<Generation>) {
        if (retired.isEmpty()) return
        withContext(NonCancellable + Dispatchers.IO) {
            retired.forEach { generation ->
                try { generation.loader.close() } catch (e: Exception) { report(generation.binding.id, e) }
            }
        }
    }

    private fun report(id: String, error: Throwable) = logger.warn("Plugin $id failed: ${error.message}", error)

}

internal val PLUGIN_ID = Regex("[a-z][a-z0-9_]{0,31}")
private val SHARED_PACKAGES = listOf("ru/souz/plugins/Plugin", "ru/souz/plugins/SouzPlugin", "ru/souz/plugins/Data", "ru/souz/plugins/Http", "ru/souz/llms/", "ru/souz/skilloauth/", "kotlin/", "kotlinx/coroutines/", "com/fasterxml/jackson/")

private fun digest(path: Path): String {
    val hash = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
