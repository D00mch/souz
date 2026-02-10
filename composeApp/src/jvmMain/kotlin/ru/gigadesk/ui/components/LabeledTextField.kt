package ru.gigadesk.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.gigadesk.ui.glassColors

@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    isError: Boolean = false,
) {
    val textColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.glassColors.textPrimary
    val borderColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.glassColors.textPrimary.copy(alpha = 0.3f)
    val focusedBorderColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    val labelColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.glassColors.textPrimary

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            isError = isError,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = textColor,
                unfocusedTextColor = textColor,
                cursorColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                focusedBorderColor = focusedBorderColor,
                unfocusedBorderColor = borderColor,
                focusedLabelColor = labelColor,
                unfocusedLabelColor = labelColor.copy(alpha = 0.6f),
                focusedContainerColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.05f),
                unfocusedContainerColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.05f),
            ),
        )
    }
}
