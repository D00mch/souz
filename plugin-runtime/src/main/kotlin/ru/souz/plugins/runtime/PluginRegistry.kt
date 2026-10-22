package ru.souz.plugins.runtime

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.JarFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.souz.plugins.PLUGIN_API_VERSION
import ru.souz.plugins.PluginException
import ru.souz.plugins.PluginTool
import ru.souz.plugins.PluginToolDefinition
import ru.souz.plugins.SouzPlugin

data class PluginBinding(val id: String, val version: String, val digest: String)
data class InstalledPlugin(val binding: PluginBinding, val tools: List<String>)
data class LoadedPluginTool(val pluginId: String, val name: String, val definition: PluginToolDefinition, val handler: PluginTool)

/** A lease spans the entire agent execution, including its child agents. */
class PluginLease internal constructor(
    val tools: List<LoadedPluginTool>,
    private val release: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private var closed = false
    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            if (!closed) {
                closed = true
                release()
            }
        }
    }
}

/** Trusted-code loader. The mutex serializes lifecycle changes, never tool invocation. */
class PluginRegistry(
    private val directory: Path? = null,
    private val cache: Path? = directory?.resolve(".cache"),
    private val reservedNames: Set<String> = emptySet(),
    private val validateDefinition: (PluginToolDefinition) -> Unit = {},
    private val reportFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    private class Generation(
        val binding: PluginBinding,
        val loader: URLClassLoader,
        val plugin: SouzPlugin,
        val tools: List<LoadedPluginTool>,
        var leases: Int = 0,
    )

    private val mutex = Mutex()
    private val loaded = mutableMapOf<String, Generation>()
    private var active = emptyMap<String, Generation>()
    private var initialized = false
    private var closed = false
    private var poller: Job? = null

    suspend fun start(scope: CoroutineScope) {
        mutex.withLock {
            initialize()
            if (directory != null && poller == null) poller = scope.launch {
                while (isActive) {
                    delay(5_000)
                    try { reconcile() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { reportFailure("scan", e) }
                }
            }
        }
    }

    suspend fun installed(): List<InstalledPlugin> = mutex.withLock {
        initialize()
        active.values.map { InstalledPlugin(it.binding, it.tools.map(LoadedPluginTool::name)) }.sortedBy { it.binding.id }
    }

    suspend fun reconcile() = mutex.withLock {
        check(!closed) { "Plugin registry is closed" }
        scan()
        initialized = true
    }

    suspend fun acquire(bindings: List<PluginBinding>): PluginLease = mutex.withLock {
        initialize()
        require(bindings.map { it.id }.distinct().size == bindings.size) { "Duplicate plugin bindings" }
        try {
            val generations = bindings.map { binding ->
                require(binding.digest.matches(Regex("[a-f0-9]{64}"))) { "Invalid plugin digest" }
                val generation = loaded[binding.digest] ?: loadCached(binding)
                require(generation.binding == binding) { "Plugin binding does not match cached artifact" }
                generation
            }
            val tools = generations.flatMap { it.tools }
            require(tools.map { it.name }.distinct().size == tools.size) { "Plugin tool collision" }
            generations.forEach { it.leases++ }
            PluginLease(tools) {
                mutex.withLock {
                    generations.forEach { it.leases-- }
                    disposeUnused()
                }
            }
        } finally {
            disposeUnused()
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        val job = mutex.withLock { poller.also { poller = null } }
        job?.cancel()
        job?.join()
        mutex.withLock {
            closed = true
            active = emptyMap()
            disposeUnused()
        }
    }

    private suspend fun initialize() {
        check(!closed) { "Plugin registry is closed" }
        if (!initialized) {
            scan()
            initialized = true
        }
    }

    private suspend fun scan() {
        val source = directory ?: return
        // A failed directory read must not uninstall the current registry.
        val candidates = withContext(Dispatchers.IO) {
            Files.createDirectories(requireNotNull(cache))
            Files.list(source).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .sorted().map { path -> cacheArtifact(path) }.toList()
            }
        }
        val descriptors = candidates.mapNotNull { path ->
            try { path to descriptor(path) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportFailure(path.fileName.toString(), e); null }
        }
        val next = active.toMutableMap()
        // Malformed replacements cannot reveal their ID; preserve active generations for this scan.
        if (descriptors.size == candidates.size) next.keys.retainAll(descriptors.map { it.second.id }.toSet())
        try {
            for ((id, entries) in descriptors.groupBy { it.second.id }) {
                if (entries.size != 1) {
                    reportFailure(id, IllegalArgumentException("Duplicate plugin ID"))
                    continue
                }
                val (path, binding) = entries.single()
                if (next[id]?.binding == binding) continue
                try {
                    val occupied = next.filterKeys { it != id }.values.flatMap { it.tools }.map { it.name }.toSet()
                    val candidate = loaded[binding.digest] ?: load(path, binding, occupied)
                    require(candidate.tools.none { it.name in occupied }) { "Plugin tool namespace collision" }
                    next[id] = candidate
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { reportFailure(id, e) }
                catch (e: LinkageError) { reportFailure(id, e) }
            }
            active = next.toMap()
        } finally { disposeUnused() }
    }

    private fun cacheArtifact(source: Path): Path {
        val root = requireNotNull(cache)
        val temporary = Files.createTempFile(root, "install-", ".part")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            val target = root.resolve("${digest(temporary)}.jar")
            if (!Files.exists(target)) Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            else require(digest(target) == target.fileName.toString().removeSuffix(".jar")) { "Cached artifact has changed" }
            return target
        } finally { Files.deleteIfExists(temporary) }
    }

    private suspend fun descriptor(path: Path): PluginBinding = withContext(Dispatchers.IO) {
        JarFile(path.toFile()).use { jar ->
            val attributes = requireNotNull(jar.manifest) { "Plugin manifest is missing" }.mainAttributes
            val id = requireNotNull(attributes.getValue("Souz-Plugin-Id"))
            require(id.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "Invalid plugin ID" }
            val version = requireNotNull(attributes.getValue("Souz-Plugin-Version")).also {
                require(it.isNotBlank() && it.length <= 100) { "Invalid plugin version" }
            }
            PluginBinding(id, version, digest(path))
        }
    }

    private suspend fun loadCached(binding: PluginBinding): Generation {
        val path = cache?.resolve("${binding.digest}.jar")
        if (path == null || !withContext(Dispatchers.IO) { Files.isRegularFile(path) }) {
            throw PluginException("plugin_artifact_missing", "Required plugin artifact is unavailable: ${binding.id}")
        }
        require(descriptor(path) == binding) { "Cached plugin artifact has changed" }
        return load(path, binding)
    }

    private suspend fun load(path: Path, binding: PluginBinding, occupiedNames: Set<String> = emptySet()): Generation {
        val entryPoint = withContext(Dispatchers.IO) {
            JarFile(path.toFile()).use { jar ->
                val attributes = requireNotNull(jar.manifest).mainAttributes
                require(attributes.getValue("Souz-Plugin-Api") == PLUGIN_API_VERSION) { "Unsupported plugin SDK" }
                require(attributes.getValue("Class-Path").isNullOrBlank()) { "Bundle private dependencies inside the plugin JAR" }
                require(jar.entries().asSequence().none { entry ->
                    listOf("ru/souz/plugins/Plugin", "ru/souz/plugins/SouzPlugin", "kotlin/", "kotlinx/coroutines/", "com/fasterxml/jackson/")
                        .any { entry.name.startsWith(it) }
                }) { "Plugin bundles host SDK/runtime classes" }
                requireNotNull(attributes.getValue("Souz-Plugin-Class")) { "Missing plugin entry point" }
            }
        }
        val loader = URLClassLoader(arrayOf(path.toUri().toURL()), SouzPlugin::class.java.classLoader)
        var plugin: SouzPlugin? = null
        var registered = false
        try {
            withContext(Dispatchers.IO) {
                val type = Class.forName(entryPoint, true, loader)
                require(type.classLoader === loader) { "Entry point must belong to the plugin JAR" }
                plugin = type.getDeclaredConstructor().newInstance() as SouzPlugin
            }
            val instance = requireNotNull(plugin)
            val definitions = instance.tools().map { tool ->
                val definition = tool.definition.copy(inputSchema = tool.definition.inputSchema.deepCopy())
                validatePluginDefinition(definition)
                validateDefinition(definition)
                val name = "${binding.id}__${definition.name}"
                require(name.length <= 64 && name !in reservedNames) { "Reserved or oversized plugin tool name: $name" }
                require(name !in occupiedNames) { "Plugin tool namespace collision" }
                LoadedPluginTool(binding.id, name, definition, tool)
            }
            require(definitions.map { it.name }.distinct().size == definitions.size) { "Duplicate plugin tool name" }
            instance.start()
            return Generation(binding, loader, instance, definitions).also {
                loaded[binding.digest] = it
                registered = true
            }
        } finally {
            if (!registered) stopPlugin(plugin, loader, binding.id)
        }
    }

    private suspend fun disposeUnused() = withContext(NonCancellable) {
        val retired = loaded.values.filter { it.leases == 0 && active[it.binding.id] !== it }
        for (generation in retired) {
            loaded.remove(generation.binding.digest)
            stopPlugin(generation.plugin, generation.loader, generation.binding.id)
        }
    }
    // Cleanup runs in NonCancellable; a plugin's self-thrown cancellation cannot skip loader cleanup.
    // The caller's original cancellation is rethrown by load/acquire after cleanup completes.
    @Suppress("SuspendFunSwallowedCancellation")
    private suspend fun stopPlugin(plugin: SouzPlugin?, loader: URLClassLoader, id: String) = withContext(NonCancellable) {
        try { plugin?.stop() }
        catch (e: Exception) { reportFailure(id, e) }
        catch (e: LinkageError) { reportFailure(id, e) }
        finally {
            try { withContext(Dispatchers.IO) { loader.close() } }
            catch (e: java.io.IOException) { reportFailure(id, e) }
        }
    }
}

private fun digest(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
