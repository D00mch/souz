package ru.souz.ui.main.usecases

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

enum class MacOsSpeechAuthorizationStatus(val code: Int) {
    NOT_DETERMINED(0),
    DENIED(1),
    RESTRICTED(2),
    AUTHORIZED(3),
    UNSUPPORTED(4),
    ;

    companion object {
        fun fromCode(code: Int): MacOsSpeechAuthorizationStatus =
            entries.firstOrNull { it.code == code } ?: UNSUPPORTED
    }
}

interface MacOsSpeechBridgeApi {
    fun hasSpeechRecognitionUsageDescription(): Boolean

    fun authorizationStatus(): MacOsSpeechAuthorizationStatus

    fun requestAuthorizationIfNeeded()

    fun recognizeWav(path: String, locale: String): String
}

class MacOsSpeechBridge(
    private val loader: MacOsSpeechBridgeLoader = MacOsSpeechBridgeLoader(),
) : MacOsSpeechBridgeApi {

    override fun hasSpeechRecognitionUsageDescription(): Boolean {
        loader.load()
        return hasSpeechRecognitionUsageDescriptionNative()
    }

    override fun authorizationStatus(): MacOsSpeechAuthorizationStatus {
        loader.load()
        return MacOsSpeechAuthorizationStatus.fromCode(authorizationStatusNative())
    }

    override fun requestAuthorizationIfNeeded() {
        loader.load()
        requestAuthorizationIfNeededNative()
    }

    override fun recognizeWav(path: String, locale: String): String {
        loader.load()
        return recognizeWavNative(path, locale)
    }

    private external fun hasSpeechRecognitionUsageDescriptionNative(): Boolean

    private external fun authorizationStatusNative(): Int

    private external fun requestAuthorizationIfNeededNative()

    private external fun recognizeWavNative(path: String, locale: String): String
}

class MacOsSpeechBridgeLoader(
    private val osNameProvider: () -> String = { System.getProperty("os.name", "") },
    private val osArchProvider: () -> String = { System.getProperty("os.arch", "") },
    private val userHomeProvider: () -> String = { System.getProperty("user.home", "") },
    private val resourceUrlProvider: (String) -> URL? =
        { resourcePath -> MacOsSpeechBridgeLoader::class.java.classLoader.getResource(resourcePath) },
    private val resourceStreamProvider: (String) -> InputStream? =
        { resourcePath -> MacOsSpeechBridgeLoader::class.java.classLoader.getResourceAsStream(resourcePath) },
) {
    private val loaded = AtomicBoolean(false)
    private val logger = LoggerFactory.getLogger(MacOsSpeechBridgeLoader::class.java)

    fun load() {
        if (loaded.get()) return

        synchronized(this) {
            if (loaded.get()) return

            val resourceDirectory = currentResourceDirectory(
                osName = osNameProvider(),
                osArch = osArchProvider(),
            ) ?: error("Local macOS speech bridge is supported only on macOS arm64/x64.")

            val libraryPath = resolveLibraryPath(
                resourceDirectory = resourceDirectory,
                userHome = userHomeProvider(),
            )
            logger.info("Loading local macOS speech bridge from {}", libraryPath.toAbsolutePath())
            System.load(libraryPath.toAbsolutePath().toString())
            loaded.set(true)
        }
    }

    private fun resolveLibraryPath(resourceDirectory: String, userHome: String): Path {
        val resourcePath = "$resourceDirectory/$LIBRARY_FILE_NAME"
        directResourcePath(resourceUrlProvider(resourcePath))?.let { return it }
        return extractLibrary(
            resourcePath = resourcePath,
            resourceDirectory = resourceDirectory,
            userHome = userHome,
        )
    }

    private fun extractLibrary(resourcePath: String, resourceDirectory: String, userHome: String): Path {
        val resourceStream = resourceStreamProvider(resourcePath)
            ?: error("Local macOS speech bridge resource not found: $resourcePath")

        val targetDir = Path.of(userHome, ".local", "state", "souz", "native", resourceDirectory)
        Files.createDirectories(targetDir)
        val target = targetDir.resolve(LIBRARY_FILE_NAME)
        resourceStream.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        target.toFile().setExecutable(true)
        return target
    }

    internal companion object {
        const val LIBRARY_FILE_NAME = "libsouz_macos_speech_bridge.dylib"

        fun currentResourceDirectory(osName: String, osArch: String): String? = when {
            osName.contains("Mac", ignoreCase = true) &&
                (osArch.contains("aarch64", ignoreCase = true) || osArch.contains("arm64", ignoreCase = true)) ->
                "darwin-arm64"

            osName.contains("Mac", ignoreCase = true) &&
                (osArch.contains("x86_64", ignoreCase = true) || osArch.contains("amd64", ignoreCase = true)) ->
                "darwin-x64"

            else -> null
        }

        fun directResourcePath(resourceUrl: URL?): Path? {
            if (resourceUrl == null || !resourceUrl.protocol.equals("file", ignoreCase = true)) {
                return null
            }
            return runCatching { Path.of(resourceUrl.toURI()) }
                .getOrNull()
                ?.takeIf(Files::exists)
        }
    }
}

internal fun writePcmToTempWav(
    rawPcm: ByteArray,
    sampleRateHz: Int = 16_000,
    channels: Int = 1,
    bitsPerSample: Int = 16,
): Path {
    val wavPath = Files.createTempFile("souz_local_macos_stt_", ".wav")
    Files.write(
        wavPath,
        pcm16MonoToWav(
            rawPcm = rawPcm,
            sampleRateHz = sampleRateHz,
            channels = channels,
            bitsPerSample = bitsPerSample,
        )
    )
    return wavPath
}

internal fun isCurrentMacOsSpeechHost(): Boolean =
    MacOsSpeechBridgeLoader.currentResourceDirectory(
        osName = System.getProperty("os.name", ""),
        osArch = System.getProperty("os.arch", ""),
    ) != null

internal fun pcm16MonoToWav(
    rawPcm: ByteArray,
    sampleRateHz: Int,
    channels: Int,
    bitsPerSample: Int,
): ByteArray {
    val byteRate = sampleRateHz * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    return ByteArrayOutputStream(WAV_HEADER_SIZE + rawPcm.size).apply {
        writeAscii("RIFF")
        writeLeInt(36 + rawPcm.size)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeLeInt(16)
        writeLeShort(1)
        writeLeShort(channels)
        writeLeInt(sampleRateHz)
        writeLeInt(byteRate)
        writeLeShort(blockAlign)
        writeLeShort(bitsPerSample)
        writeAscii("data")
        writeLeInt(rawPcm.size)
        write(rawPcm)
    }.toByteArray()
}

private const val WAV_HEADER_SIZE = 44

private fun ByteArrayOutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun ByteArrayOutputStream.writeLeShort(value: Int) {
    write(
        ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value.toShort())
            .array()
    )
}

private fun ByteArrayOutputStream.writeLeInt(value: Int) {
    write(
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()
    )
}
