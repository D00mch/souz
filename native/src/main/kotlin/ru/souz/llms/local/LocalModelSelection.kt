package ru.souz.llms.local

import java.nio.file.Path
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

data class LocalModelDownloadPrompt(
    val model: LLMModel,
    val profile: LocalModelProfile,
    val downloads: List<LocalDownloadableProfile>,
    private val rootDir: Path,
) {
    fun targetPath(profile: LocalDownloadableProfile): String =
        rootDir.resolve(profile.id).resolve(profile.ggufFilename).toAbsolutePath().toString()
}

data class LocalModelDownloadState(
    val prompt: LocalModelDownloadPrompt,
    val progress: LocalModelDownloadProgress = LocalModelDownloadProgress(
        bytesDownloaded = 0,
        totalBytes = null,
    ),
) {
    val fraction: Float?
        get() = progress.fraction
}

fun LocalModelStore.downloadPromptFor(model: LLMModel): LocalModelDownloadPrompt? {
    if (model.provider != LlmProvider.LOCAL) return null
    val profile = LocalModelProfiles.forAlias(model.alias) ?: return null
    val missingDownloads = profile.requiredDownloadProfiles().filterNot(::isPresent)
    if (missingDownloads.isEmpty()) return null

    return LocalModelDownloadPrompt(
        model = model,
        profile = profile,
        downloads = missingDownloads,
        rootDir = rootDir,
    )
}
