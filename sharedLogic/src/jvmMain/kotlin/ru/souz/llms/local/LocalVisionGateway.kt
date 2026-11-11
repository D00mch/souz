package ru.souz.llms.local

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.runtime.ImageSource
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.runtime.VisionInput
import ru.souz.llms.runtime.requestVisionText

class LocalVisionGateway(
    private val settingsProvider: SettingsProvider,
    private val chatApi: LLMChatAPI,
) : VisionGateway {
    override suspend fun analyze(input: VisionInput): String =
        withLocalVisionImages(input.images) { paths ->
            requestVisionText(settingsProvider, input, paths, chatApi::message)
        }
}

/** Native inference consumes paths; keep projected pixels alive only until the request finishes. */
suspend fun <T> withLocalVisionImages(images: List<ImageSource>, block: suspend (List<String>) -> T): T {
    val paths = mutableListOf<Path>()
    try {
        withContext(Dispatchers.IO) {
            images.forEach { image ->
                val projection = image.forVision()
                val extension = projection.substringAfter("data:image/").substringBefore(';')
                val path = Files.createTempFile("souz-vision-", ".$extension")
                paths.add(path)
                Files.write(path, Base64.getDecoder().decode(projection.substringAfter(',')))
            }
        }
        return block(paths.map { it.toString() })
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { paths.forEach { Files.deleteIfExists(it) } }
    }
}
