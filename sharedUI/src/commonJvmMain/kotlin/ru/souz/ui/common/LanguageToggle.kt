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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.souz.ui.souzColors
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.region_profile_en_label
import souz.sharedui.generated.resources.region_profile_ru_label

data class SegmentedToggleOption<T>(
    val value: T,
    val label: StringResource,
)

@Composable
fun LanguageToggle(
    useEnglish: Boolean,
    onLanguageChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    russianLabel: StringResource = Res.string.region_profile_ru_label,
    englishLabel: StringResource = Res.string.region_profile_en_label,
) {
    SettingsSegmentedToggle(
        selected = useEnglish,
        options = listOf(
            SegmentedToggleOption(value = false, label = russianLabel),
            SegmentedToggleOption(value = true, label = englishLabel),
        ),
        onSelected = onLanguageChange,
        modifier = modifier,
    )
}

@Composable
fun <T> SettingsSegmentedToggle(
    selected: T,
    options: List<SegmentedToggleOption<T>>,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.souzColors.settings.segmentContainer)
            .border(1.dp, MaterialTheme.souzColors.settings.inputBorder, RoundedCornerShape(10.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            ToggleSegment(
                text = stringResource(option.label),
                selected = selected == option.value,
                onClick = {
                    if (selected != option.value) onSelected(option.value)
                },
                modifier = Modifier.weight(1f),
            )
        }
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
            .background(if (selected) MaterialTheme.souzColors.settings.selectedSegmentBackground else Color.Transparent)
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
            color = if (selected) MaterialTheme.souzColors.settings.selectedSegmentContent else MaterialTheme.souzColors.settings.segmentContent,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}
