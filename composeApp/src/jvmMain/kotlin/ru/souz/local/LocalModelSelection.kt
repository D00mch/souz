package ru.souz.local

import ru.souz.giga.GigaModel
import ru.souz.giga.LlmProvider

data class LocalModelDownloadPrompt(
    val model: GigaModel,
    val profile: LocalModelProfile,
    val targetPath: String,
)

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

fun LocalModelStore.downloadPromptFor(model: GigaModel): LocalModelDownloadPrompt? {
    if (model.provider != LlmProvider.LOCAL) return null
    val profile = LocalModelProfiles.forAlias(model.alias) ?: return null
    if (isPresent(profile)) return null

    return LocalModelDownloadPrompt(
        model = model,
        profile = profile,
        targetPath = modelPath(profile).toAbsolutePath().toString(),
    )
}
