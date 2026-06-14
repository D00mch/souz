package ru.souz.ui.common

import ru.souz.llms.LLMModel

data class LocalModelDownloadItemUi(
    val id: String,
    val displayName: String,
    val huggingFaceRepoId: String,
    val quantization: String,
    val license: String,
    val requiresManualLicenseAcceptance: Boolean,
    val downloadUrl: String,
    val targetPath: String,
)

data class LocalModelDownloadPromptUi(
    val model: LLMModel,
    val profileId: String,
    val profileDisplayName: String,
    val downloads: List<LocalModelDownloadItemUi>,
) {
    fun targetPath(profile: LocalModelDownloadItemUi): String = profile.targetPath
}

data class LocalModelDownloadProgressUi(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val activeProfileName: String? = null,
    val completedProfiles: Int = 0,
    val totalProfiles: Int = 1,
) {
    val fraction: Float?
        get() = totalBytes
            ?.takeIf { it > 0L }
            ?.let { total -> (bytesDownloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}

data class LocalModelDownloadStateUi(
    val prompt: LocalModelDownloadPromptUi,
    val progress: LocalModelDownloadProgressUi = LocalModelDownloadProgressUi(
        bytesDownloaded = 0,
        totalBytes = null,
    ),
) {
    val fraction: Float?
        get() = progress.fraction
}
