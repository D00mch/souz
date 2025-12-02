package ru.abledo.ui.settings

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI
import ru.abledo.ui.AppTheme
import ru.abledo.ui.glassColors
import ru.abledo.ui.main.GlassCard

private val SettingsWindowSize = DpSize(width = 560.dp, height = 520.dp)

@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onResizeRequest: (DpSize) -> Unit = {}
) {
    val di = localDI()
    val viewModel = viewModel { SettingsViewModel(di) }
    val state = viewModel.uiState.collectAsState().value
    SettingsScreen(
        state,
        onGigaChatKeyInput = { key -> viewModel.send(SettingsEvent.InputGigaChatKey(key)) },
        onSaluteSpeechKeyInput = { key -> viewModel.send(SettingsEvent.InputSaluteSpeechKey(key)) },
        onUseFewShotExamplesChange = { enabled -> viewModel.send(SettingsEvent.InputUseFewShotExamples(enabled)) },
        onResizeRequest = onResizeRequest,
        onClose = onClose,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsState,
    onGigaChatKeyInput: (String) -> Unit,
    onSaluteSpeechKeyInput: (String) -> Unit,
    onUseFewShotExamplesChange: (Boolean) -> Unit,
    onResizeRequest: (DpSize) -> Unit = {},
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) { onResizeRequest(SettingsWindowSize) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Настройки",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.glassColors.textPrimary
                        )
                        Text(
                            text = "Ключи и поведение",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Закрыть настройки",
                            tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f)
                        )
                    }
                }

                LabeledTextField(
                    label = "GigaChat ключ",
                    value = state.gigaChatKey,
                    onValueChange = onGigaChatKeyInput,
                    modifier = Modifier.fillMaxWidth()
                )
                LabeledTextField(
                    label = "SaluteSpeech ключ",
                    value = state.saluteSpeechKey,
                    onValueChange = onSaluteSpeechKeyInput,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Checkbox(
                        checked = state.useFewShotExamples,
                        onCheckedChange = onUseFewShotExamplesChange,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f),
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    Text(
                        text = "Класть примеры использования тулов в контекст",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.glassColors.textPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.glassColors.textPrimary
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = MaterialTheme.glassColors.textPrimary,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                unfocusedBorderColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.3f),
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f),
            ),
        )
    }
}

@Preview
@Composable
fun SettingsScreenPreview() {
    AppTheme {
        SettingsScreen(
            state = SettingsState("key1", "key2", true),
            onGigaChatKeyInput = {},
            onSaluteSpeechKeyInput = {},
            onUseFewShotExamplesChange = {},
            onResizeRequest = {},
            onClose = {},
        )
    }
}