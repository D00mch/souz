package ru.gigadesk.ui.settings

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.gigadesk.ui.AppTheme
import ru.gigadesk.ui.glassColors

@Composable
fun SettingsSidebar(
    activeSection: SettingsSection,
    onSectionSelected: (SettingsSection) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(vertical = 24.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 12.dp)
        ) {
            Text(
                text = "Настройки",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.glassColors.textPrimary
            )
            Text(
                text = "Конфигурация",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.65f)
            )
        }

        Column(
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

@Composable
private fun SettingsSidebarItem(
    section: SettingsSection,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isActive) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    } else {
        Color.Transparent
    }

    val border = if (isActive) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    } else {
        null
    }
    
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.glassColors.textPrimary.copy(alpha = 0.7f)
    }

    val iconColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.glassColors.textPrimary.copy(alpha = 0.5f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .then(if (border != null) Modifier.border(border, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = getIconForSection(section),
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = section.title,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium
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

@Preview
@Composable
private fun SettingsSidebarPreview() {
    AppTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0E11))
                .padding(24.dp)
        ) {
            SettingsSidebar(
                activeSection = SettingsSection.MODELS,
                onSectionSelected = {},
                onClose = {},
                modifier = Modifier.fillMaxHeight()
            )
        }
    }
}
