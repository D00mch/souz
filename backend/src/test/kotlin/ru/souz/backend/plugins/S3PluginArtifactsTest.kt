package ru.souz.backend.plugins

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.crypto.sha256Hex

class S3PluginArtifactsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `immutable private artifacts support concurrent upload and fresh cache recovery`() = runBlocking {
        val storage = AttachmentMinio.config()
        val config = S3PluginArtifactConfig(storage.endpoint, storage.bucket, storage.accessKey, storage.secretKey)
        val bytes = "independently validated jar bytes".toByteArray()
        val binding = PluginBinding("mail", "v1", bytes.sha256Hex())
        val upload = directory.resolve("upload.jar")
        Files.write(upload, bytes)
        S3PluginArtifacts(config).use { first ->
            S3PluginArtifacts(config).use { second ->
                coroutineScope { listOf(first, second).map { async { it.store(upload, binding) } }.awaitAll() }
            }
        }
        Files.delete(upload)
        S3PluginArtifacts(config).use { restarted ->
            val target = directory.resolve("fresh/cache/artifact.jar")
            restarted.download(binding, target)
            assertContentEquals(bytes, Files.readAllBytes(target))
        }
        val listing = AttachmentMinio.server.execInContainer("mc", "stat", "root/${storage.bucket}/plugins/${binding.digest}.jar")
        assertEquals(0, listing.exitCode)
    }

    @Test
    fun `corrupt authoritative bytes fail without replacing an existing local file or overwriting S3`() = runBlocking {
        val storage = AttachmentMinio.config()
        val config = S3PluginArtifactConfig(storage.endpoint, storage.bucket, storage.accessKey, storage.secretKey)
        val original = "approved artifact".toByteArray()
        val binding = PluginBinding("mail", "v1", original.sha256Hex())
        val upload = directory.resolve("upload.jar")
        Files.write(upload, original)
        S3PluginArtifacts(config).use { objects ->
            objects.store(upload, binding)
            AttachmentMinio.upload(storage, "operator", "artifact.jar", "corrupt bytes".toByteArray(),
                objectKey = "plugins/${binding.digest}.jar")
            val target = directory.resolve("cache.jar")
            Files.writeString(target, "untouched")
            assertEquals("plugin_artifact_corrupt", assertFailsWith<PluginRegistryException> {
                objects.download(binding, target)
            }.code)
            assertEquals("untouched", Files.readString(target))
            assertEquals("plugin_artifact_corrupt", assertFailsWith<PluginRegistryException> {
                objects.store(upload, binding)
            }.code)
            assertFalse(Files.list(directory).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".part") } })
        }
    }

    @Test
    fun `upload verifies bytes and enforces archive bound before contacting storage`() = runBlocking {
        val config = S3PluginArtifactConfig("http://127.0.0.1:1", "plugin-artifacts", "test", "secret")
        val upload = directory.resolve("upload.jar")
        Files.writeString(upload, "different")
        val binding = PluginBinding("mail", "v1", "approved".sha256Hex())
        S3PluginArtifacts(config).use { objects ->
            assertEquals("plugin_artifact_corrupt", assertFailsWith<PluginRegistryException> { objects.store(upload, binding) }.code)
            RandomAccessFile(upload.toFile(), "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
            assertEquals("plugin_artifact_too_large", assertFailsWith<PluginRegistryException> { objects.store(upload, binding) }.code)
        }
    }
}
