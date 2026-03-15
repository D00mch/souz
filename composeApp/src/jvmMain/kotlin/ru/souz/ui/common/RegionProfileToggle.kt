package ru.souz.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.souz.ui.settings.SettingsUiColors

private val ToggleBackground = SettingsUiColors.inputBackground
private val ToggleBorder = SettingsUiColors.inputBorder
private val ToggleSelectedBackground = SettingsUiColors.toggleActiveBackground
private val ToggleSelectedText = SettingsUiColors.toggleActiveText
private val ToggleText = SettingsUiColors.toggleInactiveText

@Composable
fun RegionProfileToggle(
    useEnglishProfile: Boolean,
    onProfileChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ToggleBackground)
            .border(1.dp, ToggleBorder, RoundedCornerShape(10.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToggleSegment(
            text = "Русский (RU)",
            selected = !useEnglishProfile,
            onClick = {
                if (useEnglishProfile) onProfileChange(false)
            },
            modifier = Modifier.weight(1f)
        )
        ToggleSegment(
            text = "English (EN)",
            selected = useEnglishProfile,
            onClick = {
                if (!useEnglishProfile) onProfileChange(true)
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ToggleSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ToggleSelectedBackground else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = if (selected) ToggleSelectedText else ToggleText,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}
