package ru.souz.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import ru.souz.local.LocalModelDownloadPrompt
import ru.souz.local.LocalModelDownloadState
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.local_model_download_cancel
import souz.composeapp.generated.resources.local_model_download_detail_license
import souz.composeapp.generated.resources.local_model_download_detail_manual_license
import souz.composeapp.generated.resources.local_model_download_detail_quantization
import souz.composeapp.generated.resources.local_model_download_detail_repo
import souz.composeapp.generated.resources.local_model_download_detail_storage
import souz.composeapp.generated.resources.local_model_download_dialog_cancel
import souz.composeapp.generated.resources.local_model_download_dialog_confirm
import souz.composeapp.generated.resources.local_model_download_dialog_message
import souz.composeapp.generated.resources.local_model_download_dialog_title
import souz.composeapp.generated.resources.local_model_download_progress_action
import souz.composeapp.generated.resources.local_model_download_progress_known
import souz.composeapp.generated.resources.local_model_download_progress_message
import souz.composeapp.generated.resources.local_model_download_progress_title
import souz.composeapp.generated.resources.local_model_download_progress_unknown

@Composable
fun LocalModelDownloadPromptDialog(
    prompt: LocalModelDownloadPrompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        isOpen = true,
        variant = DialogVariant.WARNING,
        title = stringResource(Res.string.local_model_download_dialog_title),
        description = stringResource(Res.string.local_model_download_dialog_message).format(prompt.profile.displayName),
        confirmText = stringResource(Res.string.local_model_download_dialog_confirm),
        cancelText = stringResource(Res.string.local_model_download_dialog_cancel),
        details = buildPromptDetails(prompt),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
fun LocalModelDownloadProgressDialog(
    state: LocalModelDownloadState,
    onCancel: () -> Unit,
) {
    val progressText = state.fraction
        ?.let { fraction ->
            stringResource(Res.string.local_model_download_progress_known).format(
                formatBytes(state.progress.bytesDownloaded),
                formatBytes(state.progress.totalBytes ?: 0L),
            )
        }
        ?: stringResource(Res.string.local_model_download_progress_unknown).format(
            formatBytes(state.progress.bytesDownloaded),
        )
    val progressLabel = state.fraction
        ?.let { "${(it * 100).roundToInt()}%" }
        ?: stringResource(Res.string.local_model_download_progress_action)

    ConfirmDialog(
        type = ConfirmDialogType.INFO,
        title = stringResource(Res.string.local_model_download_progress_title),
        message = stringResource(Res.string.local_model_download_progress_message).format(state.prompt.profile.displayName),
        confirmText = progressLabel,
        cancelText = stringResource(Res.string.local_model_download_cancel),
        confirmEnabled = false,
        onConfirm = {},
        onDismiss = onCancel,
        detailsContent = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val fraction = state.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    )
                }
                Text(
                    text = progressText,
                    color = Color(0xD9FFFFFF),
                    fontSize = 13.sp,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(Res.string.local_model_download_detail_storage).format(state.prompt.targetPath),
                    color = Color(0x99FFFFFF),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
    )
}

@Composable
private fun buildPromptDetails(prompt: LocalModelDownloadPrompt): String = buildString {
    appendLine(stringResource(Res.string.local_model_download_detail_repo).format(prompt.profile.huggingFaceRepoId))
    appendLine(stringResource(Res.string.local_model_download_detail_quantization).format(prompt.profile.quantization))
    appendLine(stringResource(Res.string.local_model_download_detail_license).format(prompt.profile.licenseRequirements.summary))
    append(stringResource(Res.string.local_model_download_detail_storage).format(prompt.targetPath))
    if (prompt.profile.licenseRequirements.requiresManualAcceptance) {
        appendLine()
        appendLine()
        append(stringResource(Res.string.local_model_download_detail_manual_license))
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val precision = if (value >= 100 || unitIndex == 0) 0 else 1
    return "%.${precision}f %s".format(value, units[unitIndex])
}
