package ru.abledo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.kodein.di.compose.localDI
import ru.abledo.ui.AppTheme

@Composable
fun SettingsScreen() {
    val di = localDI()
    val viewModel = viewModel { SettingsViewModel(di) }
    val state = viewModel.uiState.collectAsState().value
    SettingsScreen(
        state,
        onGigaChatKeyInput = { key -> viewModel.send(SettingsEvent.InputGigaChatKey(key)) },
        onSaluteSpeechKeyInput = { key -> viewModel.send(SettingsEvent.InputSaluteSpeechKey(key)) },
    )
}

@Composable
fun SettingsScreen(
    state: SettingsState,
    onGigaChatKeyInput: (String) -> Unit,
    onSaluteSpeechKeyInput: (String) -> Unit,
) {
    Scaffold {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LabeledTextField(
                label = "GigaChat key",
                value = state.gigaChatKey,
                onValueChange = onGigaChatKeyInput,
                modifier = Modifier.fillMaxWidth()
            )

            LabeledTextField(
                label = "SaluteSpeech key",
                value = state.saluteSpeechKey,
                onValueChange = onSaluteSpeechKeyInput,
                modifier = Modifier.fillMaxWidth()
            )
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
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    AppTheme(forceDark = true) {
        SettingsScreen(
            state = SettingsState("", ""),
            onGigaChatKeyInput = {},
            onSaluteSpeechKeyInput = {},
        )
    }
}