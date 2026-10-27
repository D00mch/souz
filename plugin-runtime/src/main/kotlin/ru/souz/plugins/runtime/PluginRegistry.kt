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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import ru.souz.plugins.PLUGIN_API_VERSION
import ru.souz.plugins.PluginException
import ru.souz.plugins.PluginTool
import ru.souz.plugins.PluginToolDefinition
import ru.souz.plugins.SouzPlugin

data class PluginBinding(val id: String, val version: String, val digest: String)
data class InstalledPlugin(val binding: PluginBinding, val tools: List<String>)
data class LoadedPluginTool(val binding: PluginBinding, val name: String, val definition: PluginToolDefinition, val handler: PluginTool) {
    val pluginId: String get() = binding.id
}

/** Contains only operator artifact identity and host-authored diagnostics, never plugin exception text. */
data class PluginDiagnostic(val path: String, val binding: PluginBinding?, val stage: String, val reason: String)

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

/** Reconciliation is serialized; lifecycle code never holds the registry state mutex. */
class PluginRegistry(
    private val directory: Path? = null,
    private val cache: Path? = directory?.resolve(".cache"),
    private val reservedNames: Set<String> = emptySet(),
    private val validateDefinition: (PluginToolDefinition) -> Unit = {},
    private val reportFailure: (PluginDiagnostic) -> Unit = {},
    private val lifecycleTimeoutMs: Long = 30_000,
) {
    private class Generation(
        val binding: PluginBinding,
        val loader: URLClassLoader,
        val plugin: SouzPlugin,
        val tools: List<LoadedPluginTool>,
        var leases: Int = 0,
    )

    private val mutex = Mutex()
    private val reconciliation = Mutex()
    private val cleanup = Mutex()
    private val loaded = mutableMapOf<String, Generation>()
    private val installedPaths = mutableMapOf<Path, String>() // Last working identity at each installation path.
    private var active = emptyMap<String, Generation>()
    private var initialized = false
    private var closed = false
    private var poller: Job? = null

    suspend fun start(scope: CoroutineScope) {
        initialize()
        mutex.withLock {
            check(!closed) { "Plugin registry is closed" }
            if (directory != null && poller == null) poller = scope.launch {
                while (isActive) {
                    delay(5_000)
                    try { reconcile() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { diagnose(directory, null, "scan", e) }
                }
            }
        }
    }

    suspend fun installed(): List<InstalledPlugin> {
        initialize()
        return mutex.withLock {
            check(!closed) { "Plugin registry is closed" }
            active.values.map { InstalledPlugin(it.binding, it.tools.map(LoadedPluginTool::name)) }.sortedBy { it.binding.id }
        }
    }

    suspend fun reconcile() = reconciliation.withLock {
        mutex.withLock { check(!closed) { "Plugin registry is closed" } }
        try { scan() } finally { disposeUnused() }
        mutex.withLock { initialized = true }
    }

    suspend fun acquire(bindings: List<PluginBinding>): PluginLease {
        initialize()
        require(bindings.map { it.id }.distinct().size == bindings.size) { "Duplicate plugin bindings" }
        require(bindings.all { it.digest.matches(Regex("[a-f0-9]{64}")) }) { "Invalid plugin digest" }
        val generations = mutableListOf<Generation>()
        var leased = false
        try {
            suspend fun pin(binding: PluginBinding): Boolean = mutex.withLock {
                check(!closed) { "Plugin registry is closed" }
                val generation = loaded[binding.digest] ?: return@withLock false
                require(generation.binding == binding) { "Plugin binding does not match cached artifact" }
                generation.leases++
                generations += generation
                true
            }
            for (binding in bindings) {
                if (pin(binding)) continue
                reconciliation.withLock {
                    if (!pin(binding)) {
                        val candidate = loadCached(binding)
                        var published = false
                        try {
                            mutex.withLock {
                                check(!closed) { "Plugin registry is closed" }
                                loaded[binding.digest] = candidate
                                candidate.leases++
                                generations += candidate
                                published = true
                            }
                        } finally { if (!published) stopPlugin(candidate) }
                    }
                }
            }
            val tools = generations.flatMap { it.tools }
            require(tools.map { it.name }.distinct().size == tools.size) { "Plugin tool collision" }
            return PluginLease(tools) { release(generations) }.also { leased = true }
        } finally { if (!leased) release(generations) }
    }

    private suspend fun release(generations: List<Generation>) = withContext(NonCancellable) {
        mutex.withLock { generations.forEach { it.leases-- } }
        disposeUnused()
    }

    suspend fun close() = withContext(NonCancellable) {
        val job = mutex.withLock { poller.also { poller = null; closed = true } }
        job?.cancel()
        job?.join()
        reconciliation.withLock {
            mutex.withLock { active = emptyMap() }
            disposeUnused()
        }
    }

    private suspend fun initialize() {
        val ready = mutex.withLock {
            check(!closed) { "Plugin registry is closed" }
            initialized
        }
        if (!ready) reconciliation.withLock {
            if (!mutex.withLock { initialized }) {
                mutex.withLock { check(!closed) { "Plugin registry is closed" } }
                try { scan() } finally { disposeUnused() }
                mutex.withLock { initialized = true }
            }
        }
    }

    private suspend fun scan() {
        val source = directory ?: return
        // No state changes until the entire installation directory has been listed successfully.
        val paths = withContext(Dispatchers.IO) {
            Files.createDirectories(requireNotNull(cache))
            Files.list(source).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }.sorted().toList()
            }
        }
        val descriptors = mutableMapOf<Path, Pair<Path, PluginBinding>>()
        val observedIds = mutableMapOf<Path, String>()
        for (path in paths) {
            var binding: PluginBinding? = null
            var stage = "descriptor"
            try {
                binding = descriptor(path)
                observedIds[path] = binding.id
                stage = "cache"
                val cached = withContext(Dispatchers.IO) { cacheArtifact(path) }
                require(descriptor(cached) == binding) { "Artifact changed during installation" }
                descriptors[path] = cached to binding
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { diagnose(path, binding, stage, e) }
        }
        installedPaths.keys.retainAll(paths.toSet())
        val identities = (installedPaths + observedIds).entries.groupBy({ it.value }, { it.key })
        mutex.withLock { active = active.filterKeys { it in identities || it in installedPaths.values } }
        for ((id, locations) in identities) {
            if (locations.size != 1) {
                reportFailure(PluginDiagnostic(locations.joinToString(), null, "identity", "Duplicate plugin ID: $id; publish one JAR per ID"))
                continue
            }
            val sourcePath = locations.single()
            val (path, binding) = descriptors[sourcePath] ?: continue
            val current = mutex.withLock { active[id] }
            if (current?.binding == binding) { installedPaths[sourcePath] = id; continue }
            try {
                val occupied = mutex.withLock { active.filterKeys { it != id }.values.flatMap { it.tools }.map { it.name }.toSet() }
                // Pin an already loaded generation while a retired lease may be released concurrently.
                val retained = mutex.withLock { loaded[binding.digest]?.also { it.leases++ } }
                val candidate = retained ?: load(path, binding, occupied, sourcePath)
                var published = false
                try {
                    require(candidate.tools.none { it.name in occupied }) { "Plugin tool namespace collision" }
                    mutex.withLock {
                        check(!closed) { "Plugin registry is closed" }
                        loaded[binding.digest] = candidate
                        active = active + (id to candidate)
                        installedPaths[sourcePath] = id
                        published = true
                    }
                } finally {
                    if (retained != null) release(listOf(retained))
                    else if (!published) stopPlugin(candidate)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { diagnose(sourcePath, binding, "activation", e) }
            catch (e: LinkageError) { diagnose(sourcePath, binding, "activation", e) }
        }
        mutex.withLock { active = active.filterKeys { it in installedPaths.values || it in observedIds.values } }
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
                require(it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl)) { "Invalid plugin version" }
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

    private suspend fun load(path: Path, binding: PluginBinding, occupiedNames: Set<String> = emptySet(), source: Path = path): Generation {
        val entryPoint = withContext(Dispatchers.IO) {
            JarFile(path.toFile()).use { jar ->
                val attributes = requireNotNull(jar.manifest).mainAttributes
                if (attributes.getValue("Souz-Plugin-Api") != PLUGIN_API_VERSION) throw LifecycleFailure("Unsupported plugin SDK; expected API $PLUGIN_API_VERSION")
                if (!attributes.getValue("Class-Path").isNullOrBlank()) throw LifecycleFailure("Manifest Class-Path is forbidden; bundle private dependencies inside the JAR")
                require(jar.entries().asSequence().none { entry ->
                    listOf("ru/souz/plugins/Plugin", "ru/souz/plugins/SouzPlugin", "kotlin/", "kotlinx/coroutines/", "com/fasterxml/jackson/")
                        .any { entry.name.startsWith(it) }
                }) { "Plugin bundles host SDK/runtime classes" }
                requireNotNull(attributes.getValue("Souz-Plugin-Class")) { "Missing plugin entry point" }
            }
        }
        val loader = URLClassLoader(arrayOf(path.toUri().toURL()), SouzPlugin::class.java.classLoader)
        var plugin: SouzPlugin? = null
        var prepared = false
        var stage = "initialize"
        try {
            val generation = withContext(Dispatchers.IO) {
                withTimeoutOrNull(lifecycleTimeoutMs) {
                    val type = Class.forName(entryPoint, true, loader)
                    require(type.classLoader === loader) { "Entry point must belong to the plugin JAR" }
                    val instance = type.getDeclaredConstructor().newInstance() as SouzPlugin
                    plugin = instance
                    stage = "definitions"
                    val definitions = instance.tools().map { tool ->
                        val definition = tool.definition.copy(inputSchema = tool.definition.inputSchema.deepCopy())
                        validatePluginDefinition(definition)
                        validateDefinition(definition)
                        val name = "${binding.id}__${definition.name}"
                        require(name.length <= 64 && name !in reservedNames) { "Reserved or oversized plugin tool name" }
                        require(name !in occupiedNames) { "Plugin tool namespace collision" }
                        LoadedPluginTool(binding, name, definition, tool)
                    }
                    require(definitions.map { it.name }.distinct().size == definitions.size) { "Duplicate plugin tool name" }
                    stage = "start"
                    instance.start()
                    Generation(binding, loader, instance, definitions)
                }
            } ?: throw LifecycleFailure("$stage exceeded cooperative deadline (${lifecycleTimeoutMs}ms)")
            prepared = true
            return generation
        } catch (e: CancellationException) { throw e }
        catch (e: LifecycleFailure) { throw e }
        catch (e: Exception) { throw LifecycleFailure("$stage failed (${e.javaClass.simpleName}); check plugin packaging, schema and lifecycle implementation") }
        catch (e: LinkageError) { throw LifecycleFailure("$stage failed (${e.javaClass.simpleName}); check SDK and bundled dependencies") }
        finally { if (!prepared) stopPlugin(plugin, loader, binding, source) }
    }

    private suspend fun disposeUnused() = withContext(NonCancellable) {
        // Shutdown also joins cleanup already claimed by a concurrent lease release.
        cleanup.withLock {
            val retired = mutex.withLock {
                loaded.values.filter { it.leases == 0 && active[it.binding.id] !== it }.also { generations ->
                    generations.forEach { loaded.remove(it.binding.digest) }
                }
            }
            retired.forEach { stopPlugin(it) }
        }
    }

    private suspend fun stopPlugin(generation: Generation) =
        stopPlugin(generation.plugin, generation.loader, generation.binding, cache!!.resolve("${generation.binding.digest}.jar"))

    // Cleanup cannot be skipped by caller cancellation or a plugin's self-thrown cancellation.
    @Suppress("SuspendFunSwallowedCancellation")
    private suspend fun stopPlugin(plugin: SouzPlugin?, loader: URLClassLoader, binding: PluginBinding, path: Path) = withContext(NonCancellable) {
        try { withContext(Dispatchers.IO) { withTimeout(lifecycleTimeoutMs) { plugin?.stop() } } }
        catch (e: Exception) { diagnose(path, binding, "stop", e) }
        catch (e: LinkageError) { diagnose(path, binding, "stop", e) }
        finally {
            try { withContext(Dispatchers.IO) { loader.close() } }
            catch (e: java.io.IOException) { diagnose(path, binding, "close loader", e) }
        }
    }

    private fun diagnose(path: Path, binding: PluginBinding?, stage: String, error: Throwable) {
        val reason = when (error) {
            is LifecycleFailure -> error.message!!
            is TimeoutCancellationException -> "Cooperative lifecycle deadline exceeded (${lifecycleTimeoutMs}ms)"
            else -> when (stage) {
                "descriptor" -> "Unreadable JAR or invalid manifest; verify plugin ID/version and atomic publication"
                "cache" -> "Cannot cache or verify artifact; check permissions, disk space and cached digest integrity"
                "activation" -> "Invalid SDK, bundled dependencies, schema or tool namespace; verify plugin packaging"
                "scan" -> "Cannot read installation directory; check path and permissions"
                else -> "Plugin lifecycle/loader cleanup failed; check plugin implementation"
            } + " (${error.javaClass.simpleName})"
        }
        reportFailure(PluginDiagnostic(path.toString(), binding, stage, reason))
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

private class LifecycleFailure(message: String) : Exception(message)
