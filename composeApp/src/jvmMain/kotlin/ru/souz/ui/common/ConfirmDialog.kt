package ru.souz.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.dialog_confirm
import souz.composeapp.generated.resources.dialog_cancel
import org.jetbrains.compose.resources.stringResource

enum class ConfirmDialogType(
    val icon: ImageVector,
    val iconColor: Color,
    val iconBgColor: Color,
    val iconBorderColor: Color,
    val confirmBtnStartColor: Color,
    val confirmBtnEndColor: Color,
    val confirmBtnBorderColor: Color,
    val confirmBtnTextColor: Color,
    val confirmBtnShadowColor: Color,
    val confirmBtnHoverShadowColor: Color
) {
    INFO(
        icon = Icons.Rounded.Info,
        iconColor = Color(0xFF12E0B5),
        iconBgColor = Color(0x2612E0B5),
        iconBorderColor = Color(0x4D12E0B5),
        confirmBtnStartColor = Color(0x4012E0B5),
        confirmBtnEndColor = Color(0x2612E0B5),
        confirmBtnBorderColor = Color(0x6612E0B5),
        confirmBtnTextColor = Color(0xFF12E0B5),
        confirmBtnShadowColor = Color(0x3312E0B5),
        confirmBtnHoverShadowColor = Color(0x4D12E0B5)
    ),
    WARNING(
        icon = Icons.Rounded.Warning,
        iconColor = Color(0xFFF59E0B),
        iconBgColor = Color(0x26F59E0B),
        iconBorderColor = Color(0x4DF59E0B),
        confirmBtnStartColor = Color(0x40F59E0B),
        confirmBtnEndColor = Color(0x26F59E0B),
        confirmBtnBorderColor = Color(0x66F59E0B),
        confirmBtnTextColor = Color(0xFFF59E0B),
        confirmBtnShadowColor = Color(0x33F59E0B),
        confirmBtnHoverShadowColor = Color(0x4DF59E0B)
    ),
    SUCCESS(
        icon = Icons.Rounded.Check,
        iconColor = Color(0xFF22C55E),
        iconBgColor = Color(0x2622C55E),
        iconBorderColor = Color(0x4D22C55E),
        confirmBtnStartColor = Color(0x4022C55E),
        confirmBtnEndColor = Color(0x2622C55E),
        confirmBtnBorderColor = Color(0x6622C55E),
        confirmBtnTextColor = Color(0xFF22C55E),
        confirmBtnShadowColor = Color(0x3322C55E),
        confirmBtnHoverShadowColor = Color(0x4D22C55E)
    )
}

@Composable
fun ConfirmDialog(
    type: ConfirmDialogType,
    title: String,
    message: String? = null,
    details:String? = null,
    detailsContent: (@Composable ColumnScope.() -> Unit)? = null,
    dialogMaxWidth: Dp = 320.dp,
    dialogMaxHeightFraction: Float = 0.9f,
    confirmText: String = stringResource(Res.string.dialog_confirm),
    cancelText: String = stringResource(Res.string.dialog_cancel),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val contentScrollState = rememberScrollState()

    val overlayOpacity by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(150, easing = LinearEasing)
    )

    val dialogScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.9f,
        animationSpec = tween(200, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
    )
    val dialogOpacity by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(200, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f), // Ensure it's on top
        contentAlignment = Alignment.Center
    ) {
        val effectiveMaxHeightFraction = dialogMaxHeightFraction.coerceIn(0.5f, 1f)

        // Overlay
        Box(
            modifier = Modifier
                .matchParentSize()
                .alpha(overlayOpacity)
                .background(Color(0xA6000000))
                .blur(10.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

        // Dialog Container
        Box(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .widthIn(max = dialogMaxWidth)
                .fillMaxWidth()
                .heightIn(max = maxHeight * effectiveMaxHeightFraction)
                .scale(dialogScale)
                .alpha(dialogOpacity)
                .clip(RoundedCornerShape(12.dp))
                .shadow(
                    elevation = 40.dp,
                    shape = RoundedCornerShape(12.dp),
                    ambientColor = Color(0x80000000),
                    spotColor = Color(0x80000000)
                )
        ) {
             // Backdrop Blur for Dialog
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0xF2141820))
                    .blur(30.dp)
            )

            // Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                    .verticalScroll(contentScrollState)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(type.iconBgColor)
                        .border(1.dp, type.iconBorderColor, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = type.icon,
                        contentDescription = null,
                        tint = type.iconColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Title
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xF2FFFFFF),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                // Message
                if (!message.isNullOrEmpty()) {
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0x99FFFFFF),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                // Details
                if (!details.isNullOrEmpty() || detailsContent != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x4D000000))
                            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        if (detailsContent != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                content = detailsContent
                            )
                        } else {
                            Text(
                                text = details.orEmpty(),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0x80FFFFFF),
                                lineHeight = 18.sp, // 1.5 line height (12 * 1.5 = 18)
                            )
                        }
                    }
                }

                // Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Cancel Button
                    DialogButton(
                        text = cancelText,
                        textColor = Color(0xCCFFFFFF),
                        borderColor = Color(0x26FFFFFF),
                        backgroundColor = Color(0x14FFFFFF),
                        hoverBackgroundColor = Color(0x1FFFFFFF),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )

                    // Confirm Button
                    ConfirmDialogButton(
                        text = confirmText,
                        type = type,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    textColor: Color,
    borderColor: Color,
    backgroundColor: Color,
    hoverBackgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.98f
            isHovered -> 1.02f
            else -> 1f
        },
        animationSpec = tween(200)
    )

    val currentBg = if (isHovered) hoverBackgroundColor else backgroundColor

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(currentBg)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 8.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ConfirmDialogButton(
    text: String,
    type: ConfirmDialogType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.98f
            isHovered -> 1.02f
            else -> 1f
        },
        animationSpec = tween(200)
    )
    
    val shadowColor = if (isHovered) type.confirmBtnHoverShadowColor else type.confirmBtnShadowColor
    val shadowOpacity = if (isHovered) 0.3f else 0.2f

    // Gradient Brush
    val brush = Brush.linearGradient(
        colors = listOf(type.confirmBtnStartColor, type.confirmBtnEndColor),
        start = androidx.compose.ui.geometry.Offset.Zero,
        end = androidx.compose.ui.geometry.Offset.Infinite // Simplified direction 135deg approximation
    )

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = 0.dp, // Manually drawing shadow to control color and opacity precisely if needed, or stick to Box shadow
                shape = RoundedCornerShape(8.dp),
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
             // Using manual shadow with colored background for better control
            .clip(RoundedCornerShape(8.dp))
            .background(brush)
            .border(1.dp, type.confirmBtnBorderColor, RoundedCornerShape(8.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 8.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
         // Apply shadow effect manually if needed, but for now simple shadow is handled by modifier
        Text(
            text = text,
            color = type.confirmBtnTextColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
