package ru.souz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.souz.ui.souzColors

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
    height: Dp? = null,
    readOnly: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val settingsColors = MaterialTheme.souzColors.settings
    val textColor = if (isError) scheme.error else scheme.onSurface
    val borderColor = if (isError) scheme.error else settingsColors.inputBorder
    val focusedBorderColor = if (isError) scheme.error else scheme.outline
    val labelColor = if (isError) scheme.error else scheme.onSurfaceVariant
    val currentBorderColor = if (isFocused) focusedBorderColor else borderColor

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = labelColor,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (singleLine) {
                        Modifier.height(LabeledFieldSingleLineHeight)
                    } else if (height != null) {
                        Modifier.height(height)
                    } else {
                        Modifier.heightIn(min = LabeledFieldMultiLineMinHeight)
                    },
                )
                .background(settingsColors.inputBackground, LabeledFieldShape)
                .border(1.dp, currentBorderColor, LabeledFieldShape),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 12.dp,
                            end = if (trailingContent == null) 12.dp else 48.dp,
                            top = if (singleLine) 0.dp else 10.dp,
                            bottom = if (singleLine) 0.dp else 10.dp,
                        ),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = readOnly,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = textColor,
                ),
                cursorBrush = SolidColor(if (isError) scheme.error else scheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused }
                    .padding(
                        start = 12.dp,
                        end = if (trailingContent == null) 12.dp else 48.dp,
                        top = if (singleLine) 0.dp else 10.dp,
                        bottom = if (singleLine) 0.dp else 10.dp,
                    ),
            )
            trailingContent?.let { content ->
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    content()
                }
            }
        }
    }
}
