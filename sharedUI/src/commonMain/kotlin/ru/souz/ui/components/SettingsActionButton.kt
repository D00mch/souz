package ru.souz.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.souz.ui.souzColors

enum class SettingsActionStyle { PRIMARY, SECONDARY, DESTRUCTIVE }

@Composable
fun SettingsActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: SettingsActionStyle = SettingsActionStyle.SECONDARY,
) {
    val colors = MaterialTheme.souzColors.settings
    val (containerColor, contentColor) = when (style) {
        SettingsActionStyle.PRIMARY -> colors.primaryActionContainer to colors.primaryActionContent
        SettingsActionStyle.SECONDARY -> colors.secondaryActionContainer to colors.secondaryActionContent
        SettingsActionStyle.DESTRUCTIVE -> colors.destructiveActionContainer to colors.destructiveActionContent
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.5f),
            disabledContentColor = contentColor.copy(alpha = 0.4f),
        ),
        border = when (style) {
            SettingsActionStyle.PRIMARY -> null
            SettingsActionStyle.SECONDARY -> BorderStroke(1.dp, colors.secondaryActionBorder)
            SettingsActionStyle.DESTRUCTIVE -> BorderStroke(1.dp, colors.destructiveActionBorder)
        },
    ) {
        Text(text)
    }
}
