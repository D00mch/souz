package ru.souz.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.jetbrains.compose.resources.stringResource
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.dialog_cancel
import souz.composeapp.generated.resources.dialog_confirm

private object ConfirmDialogColors {
    val backdrop = Color(0x99000000)
    val dialogBg = Color(0xF21A1A1D)
    val dialogBorder = Color(0x1FFFFFFF)
    val iconBg = Color(0x33FFFFFF)
    val iconBorder = Color(0x26FFFFFF)
    val iconColorInfo = Color(0xFF38BDF8)
    val iconColorWarning = Color(0xFFFBBF24)
    val iconColorSuccess = Color(0xFF4ADE80)
    val titleColor = Color(0xE6FFFFFF)
    val descriptionColor = Color(0x80FFFFFF)
    val detailsBg = Color(0x08FFFFFF)
    val detailsBorder = Color(0x0FFFFFFF)
    val detailsText = Color(0x99FFFFFF)
    val actionsBorder = Color(0x0FFFFFFF)
    val cancelBg = Color(0x0DFFFFFF)
    val cancelBorder = Color(0x14FFFFFF)
    val cancelText = Color(0xB3FFFFFF)
    val cancelHoverBg = Color(0x14FFFFFF)
    val cancelHoverBorder = Color(0x1FFFFFFF)
    val cancelHoverText = Color(0xE6FFFFFF)
    val confirmBg = Color(0x1FFFFFFF)
    val confirmBorder = Color(0x26FFFFFF)
    val confirmText = Color(0xFFFFFFFF)
    val confirmHoverBg = Color(0x26FFFFFF)
    val confirmHoverBorder = Color(0x33FFFFFF)
}

enum class ConfirmDialogType {
    INFO,
    WARNING,
    SUCCESS
}

enum class DialogVariant {
    INFO,
    WARNING
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
    details: String? = null
) {
    ConfirmDialogInternal(
        isOpen = isOpen,
        icon = when (variant) {
            DialogVariant.INFO -> Icons.Outlined.Info
            DialogVariant.WARNING -> Icons.Outlined.Warning
        },
        iconTint = when (variant) {
            DialogVariant.INFO -> ConfirmDialogColors.iconColorInfo
            DialogVariant.WARNING -> ConfirmDialogColors.iconColorWarning
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
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val icon = when (type) {
        ConfirmDialogType.INFO -> Icons.Outlined.Info
        ConfirmDialogType.WARNING -> Icons.Outlined.Warning
        ConfirmDialogType.SUCCESS -> Icons.Rounded.Check
    }
    val iconTint = when (type) {
        ConfirmDialogType.INFO -> ConfirmDialogColors.iconColorInfo
        ConfirmDialogType.WARNING -> ConfirmDialogColors.iconColorWarning
        ConfirmDialogType.SUCCESS -> ConfirmDialogColors.iconColorSuccess
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
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!isOpen) return

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "confirm_dialog_alpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.95f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "confirm_dialog_scale"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f),
        contentAlignment = Alignment.Center
    ) {
        val shape = RoundedCornerShape(16.dp)
        val scrollState = rememberScrollState()
        val normalizedHeightFraction = dialogMaxHeightFraction.coerceIn(0.5f, 1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ConfirmDialogColors.backdrop)
                .blur(12.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
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
                }
                .clip(shape)
                .pointerHoverIcon(PointerIcon.Default)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(ConfirmDialogColors.dialogBg, shape)
                    .blur(48.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ConfirmDialogColors.dialogBg, shape)
                    .border(1.dp, ConfirmDialogColors.dialogBorder, shape)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DialogIcon(
                        icon = icon,
                        tint = iconTint
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = ConfirmDialogColors.titleColor,
                            textAlign = TextAlign.Center
                        )

                        if (!description.isNullOrBlank()) {
                            Text(
                                text = description,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = ConfirmDialogColors.descriptionColor,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (!detailsText.isNullOrBlank() || detailsContent != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = ConfirmDialogColors.detailsBg,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = ConfirmDialogColors.detailsBorder,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                        ) {
                            if (detailsContent != null) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    content = detailsContent
                                )
                            } else {
                                Text(
                                    text = detailsText.orEmpty(),
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = ConfirmDialogColors.detailsText,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ConfirmDialogColors.actionsBorder)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DialogButton(
                        text = cancelText,
                        primary = false,
                        enabled = true,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )

                    DialogButton(
                        text = confirmText,
                        primary = true,
                        enabled = confirmEnabled,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogIcon(
    icon: ImageVector,
    tint: Color
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(ConfirmDialogColors.iconBg, CircleShape)
            .border(1.dp, ConfirmDialogColors.iconBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
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
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val background by animateColorAsState(
        targetValue = when {
            !enabled && primary -> ConfirmDialogColors.confirmBg.copy(alpha = 0.5f)
            !enabled -> ConfirmDialogColors.cancelBg.copy(alpha = 0.5f)
            primary && isHovered -> ConfirmDialogColors.confirmHoverBg
            primary -> ConfirmDialogColors.confirmBg
            isHovered -> ConfirmDialogColors.cancelHoverBg
            else -> ConfirmDialogColors.cancelBg
        },
        animationSpec = tween(150),
        label = "confirm_dialog_button_bg"
    )

    val border by animateColorAsState(
        targetValue = when {
            !enabled && primary -> ConfirmDialogColors.confirmBorder.copy(alpha = 0.5f)
            !enabled -> ConfirmDialogColors.cancelBorder.copy(alpha = 0.5f)
            primary && isHovered -> ConfirmDialogColors.confirmHoverBorder
            primary -> ConfirmDialogColors.confirmBorder
            isHovered -> ConfirmDialogColors.cancelHoverBorder
            else -> ConfirmDialogColors.cancelBorder
        },
        animationSpec = tween(150),
        label = "confirm_dialog_button_border"
    )

    val textColor by animateColorAsState(
        targetValue = when {
            !enabled && primary -> ConfirmDialogColors.confirmText.copy(alpha = 0.5f)
            !enabled -> ConfirmDialogColors.cancelText.copy(alpha = 0.5f)
            primary -> ConfirmDialogColors.confirmText
            isHovered -> ConfirmDialogColors.cancelHoverText
            else -> ConfirmDialogColors.cancelText
        },
        animationSpec = tween(150),
        label = "confirm_dialog_button_text"
    )

    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed) 0.98f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "confirm_dialog_button_scale"
    )

    val buttonShape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .height(40.dp)
            .scale(scale)
            .background(background, buttonShape)
            .border(1.dp, border, buttonShape)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}
