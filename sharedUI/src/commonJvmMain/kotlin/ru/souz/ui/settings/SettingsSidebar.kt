package ru.souz.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.souz.ui.souzColors
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsSidebar(
    activeSection: SettingsSection,
    onSectionSelected: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(258.dp)
            .background(MaterialTheme.souzColors.settings.sidebarBackground),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Top
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Text(
                    text = stringResource(Res.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.souzColors.settings.hoverContent
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.souzColors.settings.sidebarBorder)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SettingsSection.entries.forEach { section ->
                    SettingsSidebarItem(
                        section = section,
                        isActive = activeSection == section,
                        onClick = { onSectionSelected(section) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSidebarItem(
    section: SettingsSection,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val colors = MaterialTheme.souzColors.settings
    val backgroundColor = when {
        isActive -> colors.selectedNavigationBackground
        isHovered || isPressed -> colors.hoverBackground
        else -> Color.Transparent
    }
    val contentColor = when {
        isActive -> colors.selectedNavigationContent
        isHovered || isPressed -> colors.hoverContent
        else -> colors.navigationContent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .hoverable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = getIconForSection(section),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = org.jetbrains.compose.resources.stringResource(section.title),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor
        )
    }
}

private fun getIconForSection(section: SettingsSection): ImageVector {
    return when (section) {
        SettingsSection.MODELS -> Icons.Default.SmartToy
        SettingsSection.GENERAL -> Icons.Default.Settings
        SettingsSection.KEYS -> Icons.Default.VpnKey
        SettingsSection.FUNCTIONS -> Icons.Default.Extension
        SettingsSection.SECURITY -> Icons.Default.Security
        SettingsSection.SUPPORT -> Icons.Default.HelpOutline
    }
}
