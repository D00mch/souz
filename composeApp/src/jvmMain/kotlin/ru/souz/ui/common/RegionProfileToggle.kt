package ru.souz.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val ToggleBackground = Color(0x40000000)
private val ToggleBorder = Color(0x4DFFFFFF)
private val ToggleDivider = Color(0x26FFFFFF)
private val ToggleSelectedBackground = Color(0x3312E0B5)
private val ToggleSelectedText = Color(0xFF12E0B5)
private val ToggleText = Color(0xCCFFFFFF)

@Composable
fun RegionProfileToggle(
    useEnglishProfile: Boolean,
    onProfileChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ToggleBackground)
            .border(1.dp, ToggleBorder, RoundedCornerShape(10.dp))
    ) {
        ToggleSegment(
            text = "ru",
            selected = !useEnglishProfile,
            onClick = {
                if (useEnglishProfile) onProfileChange(false)
            },
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(ToggleDivider)
        )
        ToggleSegment(
            text = "en",
            selected = useEnglishProfile,
            onClick = {
                if (!useEnglishProfile) onProfileChange(true)
            },
        )
    }
}

@Composable
private fun ToggleSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(64.dp)
            .fillMaxHeight()
            .background(if (selected) ToggleSelectedBackground else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontStyle = if (selected) FontStyle.Italic else FontStyle.Normal,
            ),
            color = if (selected) ToggleSelectedText else ToggleText,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}
