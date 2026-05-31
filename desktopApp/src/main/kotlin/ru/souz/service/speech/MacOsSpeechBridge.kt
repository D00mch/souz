package ru.souz.service.speech

import org.slf4j.LoggerFactory
import ru.souz.runtime.files.FilesToolUtil
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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

    override fun cancelRecognition() {
        loader.load()
        cancelRecognitionNative()
    }

    override fun liveIsSupported(locale: String): Boolean {
        loader.load()
        return try {
            liveIsSupportedNative(locale)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    override fun liveStart(locale: String): Long {
        loader.load()
        return liveStartNative(locale)
    }

    override fun liveAcceptPcm(
        sessionId: Long,
        audio: ByteArray,
        sampleRateHz: Int,
        channels: Int,
        bitsPerSample: Int,
    ) {
        loader.load()
        liveAcceptPcmNative(sessionId, audio, sampleRateHz, channels, bitsPerSample)
    }

    override fun livePollEvents(sessionId: Long): String {
        loader.load()
        return livePollEventsNative(sessionId)
    }

    override fun liveFinalizeAndFinish(sessionId: Long): String {
        loader.load()
        return liveFinalizeAndFinishNative(sessionId)
    }

    override fun liveCancel(sessionId: Long) {
        loader.load()
        liveCancelNative(sessionId)
    }

    private external fun hasSpeechRecognitionUsageDescriptionNative(): Boolean

    private external fun authorizationStatusNative(): Int

    private external fun requestAuthorizationIfNeededNative()

    private external fun recognizeWavNative(path: String, locale: String): String

    private external fun cancelRecognitionNative()

    private external fun liveIsSupportedNative(locale: String): Boolean

    private external fun liveStartNative(locale: String): Long

    private external fun liveAcceptPcmNative(
        sessionId: Long,
        audio: ByteArray,
        sampleRateHz: Int,
        channels: Int,
        bitsPerSample: Int,
    )

    private external fun livePollEventsNative(sessionId: Long): String

    private external fun liveFinalizeAndFinishNative(sessionId: Long): String

    private external fun liveCancelNative(sessionId: Long)
}

class MacOsSpeechBridgeLoader(
    private val osNameProvider: () -> String = { System.getProperty("os.name", "") },
    private val osArchProvider: () -> String = { System.getProperty("os.arch", "") },
    private val userHomeProvider: () -> String = { FilesToolUtil.homeStr },
    private val resourceUrlProvider: (String) -> URL? =
        { resourcePath -> MacOsSpeechBridgeLoader::class.java.classLoader.getResource(resourcePath) },
    private val resourceStreamProvider: (String) -> InputStream? =
        { resourcePath -> MacOsSpeechBridgeLoader::class.java.classLoader.getResourceAsStream(resourcePath) },
    private val loadLibrary: (Path) -> Unit = { path -> System.load(path.toAbsolutePath().toString()) },
) {
    private val logger = LoggerFactory.getLogger(MacOsSpeechBridgeLoader::class.java)

    private val loadedLibraryPath: Path by lazy {
        val resourceDirectory = LocalMacOsSpeechHost.currentResourceDirectory(
            osName = osNameProvider(),
            osArch = osArchProvider(),
        ) ?: error("Local macOS speech bridge is supported only on macOS arm64/x64.")

        val libraryPath = resolveLibraryPath(resourceDirectory)
        logger.info("Loading local macOS speech bridge from {}", libraryPath.toAbsolutePath())
        loadLibrary(libraryPath)
        libraryPath
    }

    fun load() {
        loadedLibraryPath
    }

    private fun resolveLibraryPath(resourceDirectory: String): Path {
        val resourcePath = "$resourceDirectory/$LIBRARY_FILE_NAME"
        directResourcePath(resourceUrlProvider(resourcePath))?.let { return it }
        return extractLibrary(
            resourcePath = resourcePath,
            resourceDirectory = resourceDirectory,
            userHome = userHomeProvider(),
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

    companion object {
        const val LIBRARY_FILE_NAME = "libsouz_macos_speech_bridge.dylib"

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
