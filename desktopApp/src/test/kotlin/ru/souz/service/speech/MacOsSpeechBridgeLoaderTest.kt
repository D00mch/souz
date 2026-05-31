package ru.souz.service.speech

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacOsSpeechBridgeLoaderTest {

    @Test
    fun `load uses direct file resource path when classpath resource is unpacked`() {
        val resourceFile = Files.createTempFile("souz_macos_bridge_", ".dylib")
        var loadedPath = resourceFile

        val loader = MacOsSpeechBridgeLoader(
            osNameProvider = { "Mac OS X" },
            osArchProvider = { "arm64" },
            userHomeProvider = { error("userHomeProvider should not be used") },
            resourceUrlProvider = { resourceFile.toUri().toURL() },
            resourceStreamProvider = { error("resourceStreamProvider should not be used") },
            loadLibrary = { path -> loadedPath = path },
        )

        loader.load()

        assertEquals(resourceFile, loadedPath)
    }

    @Test
    fun `load extracts bundled library into per arch state dir when direct resource path is unavailable`() {
        val userHome = createTempDirectory("souz_macos_loader_home_")
        val binary = byteArrayOf(1, 2, 3, 4)
        var loadedPath = userHome

        val loader = MacOsSpeechBridgeLoader(
            osNameProvider = { "Mac OS X" },
            osArchProvider = { "x86_64" },
            userHomeProvider = { userHome.toString() },
            resourceUrlProvider = { null },
            resourceStreamProvider = { ByteArrayInputStream(binary) },
            loadLibrary = { path -> loadedPath = path },
        )

        loader.load()

        val expectedPath = userHome
            .resolve(".local")
            .resolve("state")
            .resolve("souz")
            .resolve("native")
            .resolve("darwin-x64")
            .resolve(MacOsSpeechBridgeLoader.LIBRARY_FILE_NAME)
        assertEquals(expectedPath, loadedPath)
        assertTrue(Files.exists(expectedPath))
        assertTrue(expectedPath.toFile().canExecute())
        assertContentEquals(binary, Files.readAllBytes(expectedPath))
    }
}
