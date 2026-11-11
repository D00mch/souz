package ru.souz.backend.plugins

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.http.routes.withPluginUploadFile

class PluginUploadCleanupTest {
    @TempDir lateinit var directory: Path

    @Test fun `cancellation during IO result handoff deletes the allocated upload`() = runBlocking {
        val path = directory.resolve("upload.jar")
        var consumed = false
        val caller = launch {
            val job = currentCoroutineContext()[Job]!!
            withPluginUploadFile(create = {
                Files.createFile(path).also { job.cancel() }
            }) { consumed = true }
        }
        caller.join()
        assertFalse(consumed)
        assertFalse(Files.exists(path))
    }

}
