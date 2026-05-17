package ru.souz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LabeledFieldTextColor = Color.White.copy(alpha = 0.9f)
private val LabeledFieldLabelColor = Color.White.copy(alpha = 0.7f)
private val LabeledFieldBackgroundColor = Color(0x0DFFFFFF)
private val LabeledFieldBorderColor = Color(0x14FFFFFF)
private val LabeledFieldFocusBorderColor = Color(0x33FFFFFF)
private val LabeledFieldAccentColor = Color(0xE6FFFFFF)
private val LabeledFieldSingleLineHeight = 42.dp
private val LabeledFieldMultiLineMinHeight = 72.dp
private val LabeledFieldShape = RoundedCornerShape(12.dp)

@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    placeholder: String = "",
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var isFocused by remember { mutableStateOf(false) }
    val textColor = if (isError) MaterialTheme.colorScheme.error else LabeledFieldTextColor
    val borderColor = if (isError) MaterialTheme.colorScheme.error else LabeledFieldBorderColor
    val focusedBorderColor = if (isError) MaterialTheme.colorScheme.error else LabeledFieldFocusBorderColor
    val labelColor = if (isError) MaterialTheme.colorScheme.error else LabeledFieldLabelColor
    val currentBorderColor = if (isFocused) focusedBorderColor else borderColor

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = labelColor
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (singleLine) Modifier.height(LabeledFieldSingleLineHeight)
                    else Modifier.heightIn(min = LabeledFieldMultiLineMinHeight)
                ),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LabeledFieldBackgroundColor, LabeledFieldShape)
                    .border(1.dp, currentBorderColor, LabeledFieldShape)
            )
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    color = LabeledFieldLabelColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 12.dp,
                            vertical = if (singleLine) 0.dp else 10.dp
                        )
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = textColor
                ),
                cursorBrush = SolidColor(if (isError) MaterialTheme.colorScheme.error else LabeledFieldAccentColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused }
                    .padding(
                        horizontal = 12.dp,
                        vertical = if (singleLine) 0.dp else 10.dp
                    )
            )
        }
    }
}
