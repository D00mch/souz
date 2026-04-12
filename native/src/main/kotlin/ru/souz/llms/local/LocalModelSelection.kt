package ru.souz.llms.local

import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

data class LocalModelDownloadTarget(
    val profile: LocalDownloadableProfile,
    val targetPath: String,
)

data class LocalModelDownloadPrompt(
    val model: LLMModel,
    val profile: LocalModelProfile,
    val downloads: List<LocalModelDownloadTarget>,
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
    val missingDownloads = LocalModelBindings.requiredDownloadProfiles(profile)
        .filterNot(::isPresent)
        .map { missingProfile ->
            LocalModelDownloadTarget(
                profile = missingProfile,
                targetPath = modelPath(missingProfile).toAbsolutePath().toString(),
            )
        }
    if (missingDownloads.isEmpty()) return null

    return LocalModelDownloadPrompt(
        model = model,
        profile = profile,
        downloads = missingDownloads,
    )
}
