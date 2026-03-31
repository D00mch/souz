package ru.souz.llms.local

import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

data class LocalModelDownloadPrompt(
    val model: LLMModel,
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

fun LocalModelStore.downloadPromptFor(model: LLMModel): LocalModelDownloadPrompt? {
    if (model.provider != LlmProvider.LOCAL) return null
    val profile = LocalModelProfiles.forAlias(model.alias) ?: return null
    if (isPresent(profile)) return null

    return LocalModelDownloadPrompt(
        model = model,
        profile = profile,
        targetPath = modelPath(profile).toAbsolutePath().toString(),
    )
}
