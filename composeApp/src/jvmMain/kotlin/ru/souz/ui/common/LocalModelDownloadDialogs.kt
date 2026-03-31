package ru.souz.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import java.util.Locale
import org.jetbrains.compose.resources.stringResource
import ru.souz.llms.local.LocalModelDownloadPrompt
import ru.souz.llms.local.LocalModelDownloadState
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
        dismissOnBackdropClick = false,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
fun LocalModelDownloadProgressDialog(
    state: LocalModelDownloadState,
    onCancel: () -> Unit,
) {
    LocalModelDownloadProgressOverlay(
        state = state,
        onCancel = onCancel,
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
    val precision = if (unitIndex == 0) 0 else 1
    return String.format(Locale.US, "%.${precision}f %s", value, units[unitIndex])
}

private object LocalModelDownloadProgressColors {
    val backdrop = Color(0x99000000)
    val dialogBg = Color(0xF21A1A1D)
    val dialogBorder = Color(0x1FFFFFFF)
    val divider = Color(0x0FFFFFFF)
    val iconBg = Color(0x14FFFFFF)
    val iconBorder = Color(0x1FFFFFFF)
    val iconColor = Color(0xB3FFFFFF)
    val pulseRing = Color(0x66FFFFFF)
    val title = Color(0xE6FFFFFF)
    val description = Color(0x80FFFFFF)
    val modelName = Color(0xB3FFFFFF)
    val progressMain = Color(0xE6FFFFFF)
    val progressSecondary = Color(0x66FFFFFF)
    val trackBg = Color(0x0FFFFFFF)
    val trackBorder = Color(0x14FFFFFF)
    val fillStart = Color(0x4DFFFFFF)
    val fillEnd = Color(0x33FFFFFF)
    val shimmer = Color(0x33FFFFFF)
    val storageBg = Color(0x08FFFFFF)
    val storageBorder = Color(0x0FFFFFFF)
    val storageLabel = Color(0x66FFFFFF)
    val storagePath = Color(0x99FFFFFF)
    val cancelBg = Color(0x0DFFFFFF)
    val cancelBorder = Color(0x14FFFFFF)
    val cancelText = Color(0xB3FFFFFF)
    val cancelHoverBg = Color(0x14FFFFFF)
    val cancelHoverBorder = Color(0x1FFFFFFF)
    val cancelHoverText = Color(0xE6FFFFFF)
    val closeHoverBg = Color(0x14FFFFFF)
    val closeColor = Color(0x66FFFFFF)
    val closeHoverColor = Color(0x99FFFFFF)
}

private data class DownloadSpeedSnapshot(
    val bytesDownloaded: Long,
    val timestampNanos: Long,
)

@Composable
private fun LocalModelDownloadProgressOverlay(
    state: LocalModelDownloadState,
    onCancel: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = false
        kotlinx.coroutines.delay(50)
        visible = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(200),
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.95f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f),
        contentAlignment = Alignment.Center,
    ) {
        val dialogShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalModelDownloadProgressColors.backdrop)
                .blur(12.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
        )

        Box(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(0.92f)
                .widthIn(max = 440.dp)
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                }
                .clip(dialogShape)
                .pointerHoverIcon(PointerIcon.Default)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(LocalModelDownloadProgressColors.dialogBg, dialogShape)
                    .blur(48.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LocalModelDownloadProgressColors.dialogBg, dialogShape)
                    .border(1.dp, LocalModelDownloadProgressColors.dialogBorder, dialogShape)
            ) {
                LocalModelDownloadHeader(
                    modelName = state.prompt.profile.displayName,
                    onCancel = onCancel,
                )

                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(LocalModelDownloadProgressColors.divider)
                )

                LocalModelDownloadBody(state = state)
                LocalModelDownloadFooter(onCancel = onCancel)
            }
        }
    }
}

@Composable
private fun LocalModelDownloadHeader(
    modelName: String,
    onCancel: () -> Unit,
) {
    val title = stringResource(Res.string.local_model_download_progress_title)
    val messageTemplate = stringResource(Res.string.local_model_download_progress_message)
    val messageParts = remember(messageTemplate) { messageTemplate.split("%1\$s", limit = 2) }
    val description = remember(messageTemplate, messageParts, modelName) {
        if (messageParts.size == 2) {
            buildAnnotatedString {
                append(messageParts[0])
                withStyle(SpanStyle(color = LocalModelDownloadProgressColors.modelName)) {
                    append(modelName)
                }
                append(messageParts[1])
            }
        } else {
            buildAnnotatedString {
                append(messageTemplate.format(modelName))
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 24.dp, end = 16.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AnimatedDownloadIcon()

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = LocalModelDownloadProgressColors.title,
            )
            Text(
                text = description,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = LocalModelDownloadProgressColors.description,
            )
        }

        LocalModelDownloadCloseButton(onClick = onCancel)
    }
}

@Composable
private fun AnimatedDownloadIcon() {
    val infiniteTransition = rememberInfiniteTransition()
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1500
                0f at 0
                -12f at 750
                0f at 1500
            },
            repeatMode = RepeatMode.Restart,
        ),
    )

    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        DownloadIconPulseRing()

        Box(
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer { translationY = offsetY }
                .background(
                    color = LocalModelDownloadProgressColors.iconBg,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                )
                .border(
                    width = 1.dp,
                    color = LocalModelDownloadProgressColors.iconBorder,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = null,
                tint = LocalModelDownloadProgressColors.iconColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DownloadIconPulseRing() {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .border(
                width = 2.dp,
                color = LocalModelDownloadProgressColors.pulseRing,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
    )
}

@Composable
private fun LocalModelDownloadCloseButton(
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(32.dp)
            .background(
                color = if (isHovered) LocalModelDownloadProgressColors.closeHoverBg else Color.Transparent,
                shape = androidx.compose.foundation.shape.CircleShape,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = stringResource(Res.string.local_model_download_cancel),
            tint = if (isHovered) {
                LocalModelDownloadProgressColors.closeHoverColor
            } else {
                LocalModelDownloadProgressColors.closeColor
            },
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun LocalModelDownloadBody(state: LocalModelDownloadState) {
    val progressText = state.fraction
        ?.let {
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
    val speedBytesPerSecond = rememberDownloadSpeed(
        resetKey = state.prompt.targetPath,
        bytesDownloaded = state.progress.bytesDownloaded,
    )
    val storageLabel = stringResource(Res.string.local_model_download_detail_storage)
        .substringBefore(':')
        .uppercase()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = progressText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = LocalModelDownloadProgressColors.progressMain,
                    )
                    Text(
                        text = speedBytesPerSecond?.let(::formatDataRate) ?: " ",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = LocalModelDownloadProgressColors.progressSecondary,
                    )
                }

                Text(
                    text = progressLabel,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = LocalModelDownloadProgressColors.progressMain,
                )
            }

            LocalModelDownloadProgressBar(progress = state.fraction)
        }

        LocalModelDownloadStorageCard(
            label = storageLabel,
            path = state.prompt.targetPath,
        )
    }
}

@Composable
private fun LocalModelDownloadProgressBar(progress: Float?) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
    )
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(shape)
            .background(LocalModelDownloadProgressColors.trackBg)
            .border(1.dp, LocalModelDownloadProgressColors.trackBorder, shape),
    ) {
        if (progress != null) {
            if (animatedProgress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                        .clip(shape)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    LocalModelDownloadProgressColors.fillStart,
                                    LocalModelDownloadProgressColors.fillEnd,
                                ),
                            ),
                        )
                ) {
                    ProgressBarShimmer()
                }
            }
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                val infiniteTransition = rememberInfiniteTransition()
                val offsetFraction by infiniteTransition.animateFloat(
                    initialValue = -0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                )
                val segmentWidth = maxWidth * 0.32f

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(segmentWidth)
                        .offset(x = maxWidth * offsetFraction)
                        .clip(shape)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    LocalModelDownloadProgressColors.fillStart,
                                    LocalModelDownloadProgressColors.fillEnd,
                                ),
                            ),
                        )
                ) {
                    ProgressBarShimmer()
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ProgressBarShimmer() {
    BoxWithConstraints(
        modifier = Modifier.matchParentSize()
    ) {
        val infiniteTransition = rememberInfiniteTransition()
        val shimmerOffset by infiniteTransition.animateFloat(
            initialValue = -0.4f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        )
        val shimmerWidth = maxWidth * 0.45f

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(shimmerWidth)
                .offset(x = maxWidth * shimmerOffset)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            LocalModelDownloadProgressColors.shimmer,
                            Color.Transparent,
                        ),
                    ),
                )
        )
    }
}

@Composable
private fun LocalModelDownloadStorageCard(
    label: String,
    path: String,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LocalModelDownloadProgressColors.storageBg)
            .border(1.dp, LocalModelDownloadProgressColors.storageBorder, shape)
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = label,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.5.sp,
                color = LocalModelDownloadProgressColors.storageLabel,
            )
            Text(
                text = path,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
                color = LocalModelDownloadProgressColors.storagePath,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LocalModelDownloadFooter(onCancel: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val background by animateColorAsState(
        targetValue = if (isHovered) {
            LocalModelDownloadProgressColors.cancelHoverBg
        } else {
            LocalModelDownloadProgressColors.cancelBg
        },
        animationSpec = tween(150),
    )
    val border by animateColorAsState(
        targetValue = if (isHovered) {
            LocalModelDownloadProgressColors.cancelHoverBorder
        } else {
            LocalModelDownloadProgressColors.cancelBorder
        },
        animationSpec = tween(150),
    )
    val textColor by animateColorAsState(
        targetValue = if (isHovered) {
            LocalModelDownloadProgressColors.cancelHoverText
        } else {
            LocalModelDownloadProgressColors.cancelText
        },
        animationSpec = tween(150),
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
    )
    val buttonShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .scale(scale)
                .background(background, buttonShape)
                .border(1.dp, border, buttonShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onCancel,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(Res.string.local_model_download_cancel),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun rememberDownloadSpeed(
    resetKey: String,
    bytesDownloaded: Long,
): Float? {
    var lastSnapshot by remember(resetKey) { mutableStateOf<DownloadSpeedSnapshot?>(null) }
    var speedBytesPerSecond by remember(resetKey) { mutableStateOf<Float?>(null) }

    LaunchedEffect(resetKey, bytesDownloaded) {
        val now = System.nanoTime()
        val previous = lastSnapshot
        if (previous != null && bytesDownloaded >= previous.bytesDownloaded) {
            val deltaBytes = bytesDownloaded - previous.bytesDownloaded
            val elapsedSeconds = (now - previous.timestampNanos) / 1_000_000_000f
            if (deltaBytes > 0L && elapsedSeconds > 0.1f) {
                val instantRate = deltaBytes / elapsedSeconds
                speedBytesPerSecond = speedBytesPerSecond
                    ?.let { (it * 0.7f) + (instantRate * 0.3f) }
                    ?: instantRate
            }
        }
        lastSnapshot = DownloadSpeedSnapshot(
            bytesDownloaded = bytesDownloaded,
            timestampNanos = now,
        )
    }

    return speedBytesPerSecond
}

private fun formatDataRate(bytesPerSecond: Float): String {
    if (bytesPerSecond <= 0f) return "0 B/s"
    val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSecond.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val precision = if (unitIndex == 0) 0 else 1
    return String.format(Locale.US, "%.${precision}f %s", value, units[unitIndex])
}
