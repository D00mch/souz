package ru.souz.ui.main.usecases

import java.net.URL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacOsSpeechBridgeLoaderTest {

    @Test
    fun `directResourcePath resolves file url`() {
        val tempFile = Files.createTempFile("macos-speech-bridge-loader-", ".dylib")
        try {
            assertEquals(tempFile, MacOsSpeechBridgeLoader.directResourcePath(tempFile.toUri().toURL()))
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `directResourcePath ignores non-file url`() {
        val jarUrl = URL("jar:file:/tmp/fake.jar!/darwin-arm64/libsouz_macos_speech_bridge.dylib")
        assertNull(MacOsSpeechBridgeLoader.directResourcePath(jarUrl))
    }
}
