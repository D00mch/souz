package ru.souz.llms.local

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

internal interface SouzLlamaBridgeLibrary : Library {
    fun souz_llama_healthcheck(): Pointer?
    fun souz_llama_runtime_create(configJson: String?, errorBuffer: ByteArray, errorBufferSize: Long): Pointer?
    fun souz_llama_runtime_destroy(runtime: Pointer?)
    fun souz_llama_model_load(runtime: Pointer, requestJson: String, errorBuffer: ByteArray, errorBufferSize: Long): Pointer?
    fun souz_llama_model_unload(runtime: Pointer, model: Pointer?)
    fun souz_llama_generate(runtime: Pointer, model: Pointer, requestJson: String, errorBuffer: ByteArray, errorBufferSize: Long): Pointer?
    fun souz_llama_embeddings(runtime: Pointer, model: Pointer, requestJson: String, errorBuffer: ByteArray, errorBufferSize: Long): Pointer?
    fun souz_llama_generate_stream(
        runtime: Pointer,
        model: Pointer,
        requestJson: String,
        callback: StreamCallback?,
        userData: Pointer?,
        errorBuffer: ByteArray,
        errorBufferSize: Long,
    ): Pointer?

    fun souz_llama_cancel(runtime: Pointer)
    fun souz_llama_string_free(value: Pointer?)

    fun interface StreamCallback : Callback {
        fun invoke(eventJson: Pointer?, userData: Pointer?)
    }
}

internal interface PosixCLibrary : Library {
    fun setenv(name: String, value: String, overwrite: Int): Int
}

class LocalBridgeLoader(
    private val hostInfoProvider: LocalHostInfoProvider,
) {
    private val l = LoggerFactory.getLogger(LocalBridgeLoader::class.java)

    private val cached = AtomicReference<LoadedBridge?>(null)

    fun healthcheck(): String {
        val library = loadLibrary()
        return pointerToString(library.souz_llama_healthcheck())
            ?: error("Bridge healthcheck returned an empty response.")
    }

    internal fun library(): SouzLlamaBridgeLibrary = loadLibrary()

    private fun loadLibrary(): SouzLlamaBridgeLibrary {
        val host = hostInfoProvider.current()
        val platform = host.platform
            ?: error("Unsupported platform for local inference: ${host.osName} (${host.osArch})")

        cached.get()?.takeIf { it.platform == platform }?.let { return it.library }

        synchronized(this) {
            cached.get()?.takeIf { it.platform == platform }?.let { return it.library }
            val extracted = extractLibrary(platform)
            configureEnvironmentBeforeLoad(platform)
            l.info("Loading local bridge from {}", extracted)
            val library = Native.load(extracted.toAbsolutePath().toString(), SouzLlamaBridgeLibrary::class.java)
            cached.set(LoadedBridge(platform = platform, path = extracted, library = library))
            return library
        }
    }

    private fun extractLibrary(platform: LocalPlatform): Path {
        val resourcePath = "${platform.resourceDirectory}/${platform.libraryFileName}"
        val resourceStream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Local bridge resource not found: $resourcePath")

        val targetDir = Path.of(
            System.getProperty("user.home"),
            ".local",
            "state",
            "souz",
            "native",
            platform.resourceDirectory,
        )
        Files.createDirectories(targetDir)
        val target = targetDir.resolve(platform.libraryFileName)
        resourceStream.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        target.toFile().setExecutable(true)
        return target
    }

    private fun configureEnvironmentBeforeLoad(platform: LocalPlatform) {
        if (platform != LocalPlatform.MACOS_ARM64 && platform != LocalPlatform.MACOS_X64) {
            return
        }
        val keepResidency = System.getenv("SOUZ_LLAMA_METAL_RESIDENCY")
            ?.let { value -> value.equals("1", ignoreCase = true) || value.equals("true", ignoreCase = true) }
            ?: false
        if (keepResidency) {
            return
        }

        val libc = Native.load("c", PosixCLibrary::class.java)
        libc.setenv("GGML_METAL_NO_RESIDENCY", "1", 1)
    }

    private fun pointerToString(pointer: Pointer?): String? = pointer?.let {
        try {
            it.getString(0)
        } finally {
            loadLibrary().souz_llama_string_free(it)
        }
    }

    private data class LoadedBridge(
        val platform: LocalPlatform,
        val path: Path,
        val library: SouzLlamaBridgeLibrary,
    )
}

class LocalNativeBridge(
    private val loader: LocalBridgeLoader,
) {
    fun healthcheck(): String = loader.healthcheck()

    fun createRuntime(): Pointer {
        val errorBuffer = ByteArray(ERROR_BUFFER_SIZE)
        val runtime = loader.library().souz_llama_runtime_create("{}", errorBuffer, errorBuffer.size.toLong())
        return runtime ?: error(errorMessage("Failed to create local runtime", errorBuffer))
    }

    fun destroyRuntime(runtime: Pointer?) {
        loader.library().souz_llama_runtime_destroy(runtime)
    }

    fun loadModel(runtime: Pointer, requestJson: String): Pointer {
        val errorBuffer = ByteArray(ERROR_BUFFER_SIZE)
        val model = loader.library().souz_llama_model_load(runtime, requestJson, errorBuffer, errorBuffer.size.toLong())
        return model ?: error(errorMessage("Failed to load local model", errorBuffer))
    }

    fun unloadModel(runtime: Pointer, model: Pointer?) {
        loader.library().souz_llama_model_unload(runtime, model)
    }

    fun generate(runtime: Pointer, model: Pointer, requestJson: String): String {
        val errorBuffer = ByteArray(ERROR_BUFFER_SIZE)
        val pointer = loader.library().souz_llama_generate(runtime, model, requestJson, errorBuffer, errorBuffer.size.toLong())
            ?: error(errorMessage("Local generation failed", errorBuffer))
        return consumeString(pointer)
    }

    fun embeddings(runtime: Pointer, model: Pointer, requestJson: String): String {
        val errorBuffer = ByteArray(ERROR_BUFFER_SIZE)
        val pointer = loader.library().souz_llama_embeddings(runtime, model, requestJson, errorBuffer, errorBuffer.size.toLong())
            ?: error(errorMessage("Local embeddings failed", errorBuffer))
        return consumeString(pointer)
    }

    fun generateStream(
        runtime: Pointer,
        model: Pointer,
        requestJson: String,
        onEvent: (String) -> Unit,
    ): String {
        val errorBuffer = ByteArray(ERROR_BUFFER_SIZE)
        val callback = SouzLlamaBridgeLibrary.StreamCallback { eventJson, _ ->
            val eventText = eventJson?.getString(0) ?: return@StreamCallback
            onEvent(eventText)
        }
        val pointer = loader.library().souz_llama_generate_stream(
            runtime = runtime,
            model = model,
            requestJson = requestJson,
            callback = callback,
            userData = null,
            errorBuffer = errorBuffer,
            errorBufferSize = errorBuffer.size.toLong(),
        ) ?: error(errorMessage("Local streaming generation failed", errorBuffer))
        return consumeString(pointer)
    }

    fun cancel(runtime: Pointer) {
        loader.library().souz_llama_cancel(runtime)
    }

    private fun consumeString(pointer: Pointer): String = try {
        pointer.getString(0)
    } finally {
        loader.library().souz_llama_string_free(pointer)
    }

    private fun errorMessage(prefix: String, buffer: ByteArray): String {
        val details = buffer.decodeToString().trimEnd('\u0000').ifBlank { "unknown error" }
        return "$prefix: $details"
    }

    private companion object {
        const val ERROR_BUFFER_SIZE = 16 * 1024
    }
}
