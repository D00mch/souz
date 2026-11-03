package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.DeserializationFeature
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.Duration
import java.util.Collections
import java.util.ServiceLoader
import java.util.jar.Manifest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.runInterruptible
import ru.souz.backend.client.normalizeToolParameters
import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.api.*
import ru.souz.tool.ToolCategory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception

internal const val PLUGIN_JAR_BYTES = 64 * 1024 * 1024
internal const val PLUGIN_SPI = "META-INF/services/ru.souz.plugins.api.SouzPlugin"
private val sharedPackages = listOf("ru.souz.plugins.api.", "kotlin.", "kotlinx.coroutines.")
private fun shared(name: String): Boolean = sharedPackages.any(name::startsWith)

/** Bounds and identity are validated before any plugin class is linked or initialized. */
internal fun inspectPlugin(path: Path): PluginBinding {
    require(Files.size(path) in 1..PLUGIN_JAR_BYTES.toLong())
    val index = ZipFile(path.toFile()).use { zip ->
        buildSet { for (entry in zip.entries()) require(size < 20_000 && add(entry.name)) }
    }
    val names = mutableSetOf<String>()
    var provider: String? = null
    var attributes: java.util.jar.Attributes? = null
    var expanded = 0L
    Files.newInputStream(path).use { input -> ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(names.size < 20_000 && names.add(entry.name))
            val name = entry.name
            val className = name.replace(Regex("^META-INF/versions/[0-9]+/"), "").replace('/', '.')
            require(!name.endsWith(".class") || !shared(className)) { "Shared runtime classes must be compile-only." }
            val limit = when (name) { "META-INF/MANIFEST.MF" -> 65_536; PLUGIN_SPI -> 8192; else -> 8 }
            val header = zip.readNBytes(limit + if (limit > 8) 1 else 0)
            expanded += header.size
            when (name) {
                "META-INF/MANIFEST.MF" -> {
                    require(header.size <= limit)
                    attributes = Manifest(header.inputStream()).mainAttributes
                }
                PLUGIN_SPI -> {
                    require(header.size <= limit)
                    val providers = header.toString(Charsets.UTF_8).lineSequence()
                        .map { it.substringBefore('#').trim() }.filter(String::isNotEmpty).toList()
                    require(providers.size == 1 && providers.single().matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]*")))
                    provider = providers.single()
                }
            }
            if (name.endsWith(".class")) {
                require(header.size == 8 && header.take(4) == listOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte()))
                require((header[6].toInt() and 255) * 256 + (header[7].toInt() and 255) <= 65) { "Plugin requires a newer JVM." }
            }
            val buffer = ByteArray(8192)
            do {
                require(expanded <= 256L * 1024 * 1024) { "Expanded JAR exceeds its bound." }
                val count = zip.read(buffer)
                if (count < 0) break
                expanded += count
            } while (true)
        }
    } }
    require(names == index && provider != null && provider!!.replace('.', '/') + ".class" in names)
    val manifest = requireNotNull(attributes)
    require(manifest.getValue("Souz-Plugin-Api") == PluginApi.ABI && manifest.getValue("Souz-Plugin-Jvm") == "21")
    require(manifest.getValue("Class-Path") == null)
    return PluginBinding(requireNotNull(manifest.getValue("Souz-Plugin-Id")), requireNotNull(manifest.getValue("Souz-Plugin-Version")),
        Files.newInputStream(path).use { copyArtifact(it, OutputStream.nullOutputStream()) }).also(::requireIdentity)
}

private fun copyArtifact(input: InputStream, output: OutputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    var length = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        length += count
        require(length <= PLUGIN_JAR_BYTES)
        digest.update(buffer, 0, count)
        output.write(buffer, 0, count)
    }
    require(length > 0)
    return java.util.HexFormat.of().formatHex(digest.digest())
}

/** Immutable objects have separate credentials and no expiry or deletion API. */
internal class S3PluginArtifacts(config: S3PluginArtifactConfig) : AutoCloseable {
    private val bucket = config.bucket
    private val client = S3Client.builder().endpointOverride(URI(config.endpoint)).region(Region.of(config.region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey)))
        .forcePathStyle(config.pathStyleAccess)
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(30)))
        .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(30)) }.build()

    suspend fun store(path: Path, binding: PluginBinding): Unit = runInterruptible(Dispatchers.IO) {
        requireIdentity(binding)
        require(Files.newInputStream(path).use { copyArtifact(it, OutputStream.nullOutputStream()) } == binding.digest)
        try {
            client.putObject({ it.bucket(bucket).key(key(binding)).ifNoneMatch("*").contentType("application/java-archive") }, RequestBody.fromFile(path))
        } catch (failure: S3Exception) {
            if (failure.statusCode() != 409 && failure.statusCode() != 412) throw failure
            read(binding, OutputStream.nullOutputStream())
        }
    }
    suspend fun download(binding: PluginBinding, target: Path): Unit = runInterruptible(Dispatchers.IO) {
        requireIdentity(binding)
        val temporary = Files.createTempFile(target.toAbsolutePath().parent, "download-", ".part")
        try {
            Files.newOutputStream(temporary).use { read(binding, it) }
            require(inspectPlugin(temporary) == binding) { "Artifact identity differs from the pinned version." }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun read(binding: PluginBinding, output: OutputStream) {
        client.getObject { it.bucket(bucket).key(key(binding)) }.use {
            if (it.response().contentLength() !in 1..PLUGIN_JAR_BYTES.toLong()) throw IOException("Invalid artifact length.")
            if (copyArtifact(it, output) != binding.digest) throw IOException("Corrupt plugin artifact.")
        }
    }
    private fun key(binding: PluginBinding) = "plugins/" + binding.digest + ".jar"
    override fun close() = client.close()
}

internal data class PluginFunction(val schema: LLMRequest.Function, val category: ToolCategory, val handler: suspend (String, PluginContext) -> String)

/** The runtime owns this handle BEFORE provider construction, including failed construction. */
internal class PluginCode(val path: Path, val binding: PluginBinding) : AutoCloseable {
    val loader = PluginClassLoader(path.toUri().toURL())
    fun functions(): List<PluginFunction> {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        try {
            val providers = ServiceLoader.load(SouzPlugin::class.java, loader).stream().toList()
            require(providers.size == 1 && providers.single().type().classLoader === loader)
            val tools = providers.single().get().tools.toList()
            require(tools.size in 1..64)
            var bytes = 0L
            val names = mutableSetOf<String>()
            return tools.map { tool ->
                val name = "plugin." + binding.id + "." + tool.name
                require(name.length <= 64 && tool.name.matches(Regex("[a-z][a-z0-9_]{0,39}")) && names.add(name))
                require(tool.description.isNotBlank() && tool.description.length <= 16_384)
                bytes += tool.description.toByteArray().size + tool.inputSchemaJson.toByteArray().size
                require(bytes <= PluginApi.MAX_JSON_BYTES)
                val schema = restJsonMapper.readerFor(LLMRequest.Parameters::class.java)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue<LLMRequest.Parameters>(tool.inputSchemaJson)
                PluginFunction(LLMRequest.Function(name, tool.description, normalizeToolParameters(schema)),
                    ToolCategory.valueOf(tool.category), tool.invoke)
            }
        } finally { thread.contextClassLoader = previous }
    }
    override fun close() { loader.close(); Files.deleteIfExists(path) }
}

internal class PluginClassLoader(url: URL) : URLClassLoader(arrayOf(url), PluginParent) {
    override fun getResource(name: String): URL? = if (shared(name.replace('/', '.'))) parent.getResource(name) else findResource(name)?.uncached()
    override fun getResources(name: String) = if (shared(name.replace('/', '.'))) parent.getResources(name)
        else Collections.enumeration(findResources(name).asSequence().map { it.uncached() }.toList())
}
private object PluginParent : ClassLoader(ClassLoader.getPlatformClassLoader()) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        if (shared(name)) SouzPlugin::class.java.classLoader.loadClass(name) else super.loadClass(name, resolve)
    override fun getResource(name: String): URL? =
        if (shared(name.replace('/', '.'))) SouzPlugin::class.java.classLoader.getResource(name) else null
    override fun getResources(name: String) =
        if (shared(name.replace('/', '.'))) SouzPlugin::class.java.classLoader.getResources(name) else Collections.emptyEnumeration()
}
private fun URL.uncached(): URL {
    val original = this
    return URL.of(toURI(), object : URLStreamHandler() {
        override fun openConnection(url: URL): URLConnection = original.openConnection().apply { useCaches = false }
    })
}
internal class PluginClassLoaderContext(private val loader: ClassLoader) :
    ThreadContextElement<ClassLoader?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PluginClassLoaderContext>
    override fun updateThreadContext(context: CoroutineContext): ClassLoader? =
        Thread.currentThread().contextClassLoader.also { Thread.currentThread().contextClassLoader = loader }
    override fun restoreThreadContext(context: CoroutineContext, oldState: ClassLoader?) { Thread.currentThread().contextClassLoader = oldState }
}
