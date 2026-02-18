package ru.souz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LabeledFieldTextColor = Color.White.copy(alpha = 0.9f)
private val LabeledFieldLabelColor = Color.White.copy(alpha = 0.9f)
private val LabeledFieldBackgroundColor = Color(0x66000000)
private val LabeledFieldBorderColor = Color(0x26FFFFFF)
private val LabeledFieldFocusBorderColor = Color(0x8012E0B5)
private val LabeledFieldAccentColor = Color(0xFF12E0B5)

@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    isError: Boolean = false,
) {
    val textColor = if (isError) MaterialTheme.colorScheme.error else LabeledFieldTextColor
    val borderColor = if (isError) MaterialTheme.colorScheme.error else LabeledFieldBorderColor
    val focusedBorderColor = if (isError) MaterialTheme.colorScheme.error else LabeledFieldFocusBorderColor
    val labelColor = if (isError) MaterialTheme.colorScheme.error else LabeledFieldLabelColor

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                ),
                color = labelColor
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (singleLine) Modifier.height(56.dp)
                    else Modifier.heightIn(min = 56.dp)
                ),
            singleLine = singleLine,
            isError = isError,
            shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 15.sp,
                lineHeight = 22.sp
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = textColor,
                unfocusedTextColor = textColor,
                cursorColor = if (isError) MaterialTheme.colorScheme.error else LabeledFieldAccentColor,
                focusedBorderColor = focusedBorderColor,
                unfocusedBorderColor = borderColor,
                focusedLabelColor = labelColor,
                unfocusedLabelColor = labelColor.copy(alpha = 0.6f),
                focusedContainerColor = LabeledFieldBackgroundColor,
                unfocusedContainerColor = LabeledFieldBackgroundColor,
            ),
        )
    }
}
