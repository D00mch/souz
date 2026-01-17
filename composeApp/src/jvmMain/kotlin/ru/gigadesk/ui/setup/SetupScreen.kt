package ru.gigadesk.ui.setup

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI
import ru.gigadesk.ui.AppTheme
import ru.gigadesk.ui.components.LabeledTextField
import ru.gigadesk.ui.glassColors
import ru.gigadesk.ui.main.RealLiquidGlassCard

private val SetupWindowSize = DpSize(width = 560.dp, height = 480.dp)

@Composable
fun SetupScreen(
    onOpenMain: () -> Unit,
    onResizeRequest: (DpSize) -> Unit = {},
) {
    val di = localDI()
    val viewModel = viewModel { SetupViewModel(di) }
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SetupEffect.OpenMain -> onOpenMain()
            }
        }
    }
    if (state.shouldProceed) onOpenMain()

    SetupScreenContent(
        state = state,
        onGigaChatKeyInput = { key -> viewModel.send(SetupEvent.InputGigaChatKey(key)) },
        onSaluteSpeechKeyInput = { key -> viewModel.send(SetupEvent.InputSaluteSpeechKey(key)) },
        onChooseVoice = { viewModel.send(SetupEvent.ChooseVoice) },
        onProceed = { viewModel.send(SetupEvent.Proceed) },
        onResizeRequest = onResizeRequest,
    )
}

@Composable
fun SetupScreenContent(
    state: SetupState,
    onGigaChatKeyInput: (String) -> Unit,
    onSaluteSpeechKeyInput: (String) -> Unit,
    onChooseVoice: () -> Unit,
    onProceed: () -> Unit,
    onResizeRequest: (DpSize) -> Unit = {},
) {
    LaunchedEffect(Unit) { onResizeRequest(SetupWindowSize) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = LocalWindowInfo.current.isWindowFocused
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Перед началом работы нужно настроить ключи",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.glassColors.textPrimary
                )

                if (state.missingMessages.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.missingMessages.forEach { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                LabeledTextField(
                    label = "GigaChat ключ",
                    value = state.gigaChatKey,
                    onValueChange = onGigaChatKeyInput,
                    modifier = Modifier.fillMaxWidth(),
                    isError = state.gigaChatKey.isBlank()
                )

                LabeledTextField(
                    label = "SaluteSpeech ключ",
                    value = state.saluteSpeechKey,
                    onValueChange = onSaluteSpeechKeyInput,
                    modifier = Modifier.fillMaxWidth(),
                    isError = state.saluteSpeechKey.isBlank()
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "Также сразу можете выбрать мне голос",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )

                OutlinedButton(
                    onClick = onChooseVoice,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Выбрать голос",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.glassColors.textPrimary
                    )
                }
                if (state.canProceed) {
                    Button(
                        onClick = onProceed,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Приступить к работе",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun SetupScreenPreview() {
    AppTheme {
        SetupScreenContent(
            state = SetupState(
                gigaChatKey = "",
                saluteSpeechKey = "",
                missingMessages = listOf(
                    "Не могу найти ключи для GigaChat и Salute Speech",
                    "Не могу найти ключи для GigaChat",
                    "Не могу найти ключи для Salute Speech"
                ),
                canProceed = false
            ),
            onGigaChatKeyInput = {},
            onSaluteSpeechKeyInput = {},
            onChooseVoice = {},
            onProceed = {},
            onResizeRequest = {},
        )
    }
}
