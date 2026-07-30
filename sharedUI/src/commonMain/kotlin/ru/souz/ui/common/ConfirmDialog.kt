package ru.souz.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.jetbrains.compose.resources.stringResource
import ru.souz.ui.souzColors
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.dialog_cancel
import souz.sharedui.generated.resources.dialog_confirm

enum class ConfirmDialogType {
    INFO,
    WARNING,
    SUCCESS,
}

enum class DialogVariant {
    INFO,
    WARNING,
}

@Composable
fun ConfirmDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    description: String? = null,
    confirmText: String = stringResource(Res.string.dialog_confirm),
    cancelText: String = stringResource(Res.string.dialog_cancel),
    variant: DialogVariant = DialogVariant.INFO,
    details: String? = null,
    dismissOnBackdropClick: Boolean = true,
) {
    val colors = MaterialTheme.souzColors.dialog
    ConfirmDialogInternal(
        isOpen = isOpen,
        icon = when (variant) {
            DialogVariant.INFO -> Icons.Outlined.Info
            DialogVariant.WARNING -> Icons.Outlined.Warning
        },
        iconTint = when (variant) {
            DialogVariant.INFO -> colors.info
            DialogVariant.WARNING -> colors.warning
        },
        title = title,
        description = description,
        detailsText = details,
        detailsContent = null,
        confirmText = confirmText,
        cancelText = cancelText,
        confirmEnabled = true,
        dialogMaxWidth = 320.dp,
        dialogMaxHeightFraction = 0.9f,
        dismissOnBackdropClick = dismissOnBackdropClick,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
fun ConfirmDialog(
    type: ConfirmDialogType,
    title: String,
    message: String? = null,
    details: String? = null,
    detailsContent: (@Composable ColumnScope.() -> Unit)? = null,
    dialogMaxWidth: Dp = 320.dp,
    dialogMaxHeightFraction: Float = 0.9f,
    confirmText: String = stringResource(Res.string.dialog_confirm),
    cancelText: String = stringResource(Res.string.dialog_cancel),
    confirmEnabled: Boolean = true,
    dismissOnBackdropClick: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.souzColors.dialog
    val icon = when (type) {
        ConfirmDialogType.INFO -> Icons.Outlined.Info
        ConfirmDialogType.WARNING -> Icons.Outlined.Warning
        ConfirmDialogType.SUCCESS -> Icons.Rounded.Check
    }
    val iconTint = when (type) {
        ConfirmDialogType.INFO -> colors.info
        ConfirmDialogType.WARNING -> colors.warning
        ConfirmDialogType.SUCCESS -> colors.success
    }

    ConfirmDialogInternal(
        isOpen = true,
        icon = icon,
        iconTint = iconTint,
        title = title,
        description = message,
        detailsText = details,
        detailsContent = detailsContent,
        confirmText = confirmText,
        cancelText = cancelText,
        confirmEnabled = confirmEnabled,
        dialogMaxWidth = dialogMaxWidth,
        dialogMaxHeightFraction = dialogMaxHeightFraction,
        dismissOnBackdropClick = dismissOnBackdropClick,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun ConfirmDialogInternal(
    isOpen: Boolean,
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String?,
    detailsText: String?,
    detailsContent: (@Composable ColumnScope.() -> Unit)?,
    confirmText: String,
    cancelText: String,
    confirmEnabled: Boolean,
    dialogMaxWidth: Dp,
    dialogMaxHeightFraction: Float,
    dismissOnBackdropClick: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!isOpen) return

    val colors = MaterialTheme.souzColors.dialog
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "confirm_dialog_alpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.95f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "confirm_dialog_scale",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(16.dp)
        val scrollState = rememberScrollState()
        val normalizedHeightFraction = dialogMaxHeightFraction.coerceIn(0.5f, 1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.backdrop)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = if (dismissOnBackdropClick) onDismiss else ({}),
                ),
        )

        Box(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = dialogMaxWidth)
                .heightIn(max = maxHeight * normalizedHeightFraction)
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                    this.clip = true
                    this.shape = shape
                }
                .clip(shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.background, shape)
                    .border(1.dp, colors.border, shape),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DialogIcon(
                        icon = icon,
                        tint = iconTint,
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.content,
                            textAlign = TextAlign.Center,
                        )

                        if (!description.isNullOrBlank()) {
                            Text(
                                text = description,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = colors.secondaryContent,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    if (!detailsText.isNullOrBlank() || detailsContent != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = colors.subtleBackground,
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .border(
                                    width = 1.dp,
                                    color = colors.subtleBorder,
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .padding(12.dp),
                        ) {
                            if (detailsContent != null) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    content = detailsContent,
                                )
                            } else {
                                Text(
                                    text = detailsText.orEmpty(),
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = colors.secondaryContent,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.subtleBorder),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DialogButton(
                        text = cancelText,
                        primary = false,
                        enabled = true,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )

                    DialogButton(
                        text = confirmText,
                        primary = true,
                        enabled = confirmEnabled,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogIcon(
    icon: ImageVector,
    tint: Color,
) {
    val colors = MaterialTheme.souzColors.dialog
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(colors.subtleBackground, CircleShape)
            .border(1.dp, colors.subtleBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun DialogButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.souzColors.dialog
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val background by animateColorAsState(
        targetValue = when {
            !enabled && primary -> colors.primaryActionBackground.copy(alpha = 0.5f)
            !enabled -> colors.secondaryActionBackground.copy(alpha = 0.5f)
            primary && isHovered -> colors.primaryActionHoverBackground
            primary -> colors.primaryActionBackground
            isHovered -> colors.secondaryActionHoverBackground
            else -> colors.secondaryActionBackground
        },
        animationSpec = tween(150),
        label = "confirm_dialog_button_bg",
    )

    val border by animateColorAsState(
        targetValue = when {
            !enabled && primary -> colors.primaryActionBackground.copy(alpha = 0.5f)
            !enabled -> colors.subtleBorder.copy(alpha = 0.5f)
            primary && isHovered -> colors.primaryActionHoverBackground
            primary -> colors.primaryActionBackground
            isHovered -> colors.border
            else -> colors.subtleBorder
        },
        animationSpec = tween(150),
        label = "confirm_dialog_button_border",
    )

    val textColor by animateColorAsState(
        targetValue = when {
            !enabled && primary -> colors.primaryActionContent.copy(alpha = 0.5f)
            !enabled -> colors.secondaryContent.copy(alpha = 0.5f)
            primary -> colors.primaryActionContent
            isHovered -> colors.content
            else -> colors.secondaryContent
        },
        animationSpec = tween(150),
        label = "confirm_dialog_button_text",
    )

    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed) 0.98f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "confirm_dialog_button_scale",
    )

    val buttonShape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .height(40.dp)
            .scale(scale)
            .background(background, buttonShape)
            .border(1.dp, border, buttonShape)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
        )
    }
}
