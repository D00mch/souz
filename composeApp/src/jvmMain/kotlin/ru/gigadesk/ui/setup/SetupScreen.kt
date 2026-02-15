package ru.gigadesk.ui.setup

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI
import ru.gigadesk.ui.AppTheme
import ru.gigadesk.ui.common.ApiKeyProvider
import ru.gigadesk.ui.components.LabeledTextField
import ru.gigadesk.ui.glassColors
import ru.gigadesk.ui.main.RealLiquidGlassCard
import ru.gigadesk.ui.common.DraggableWindowArea

private val SetupWindowSize = DpSize(width = 640.dp, height = 760.dp)

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
    LaunchedEffect(state.shouldProceed) {
        if (state.shouldProceed) onOpenMain()
    }

    SetupScreenContent(
        state = state,
        onGigaChatKeyInput = { key -> viewModel.send(SetupEvent.InputGigaChatKey(key)) },
        onQwenChatKeyInput = { key -> viewModel.send(SetupEvent.InputQwenChatKey(key)) },
        onAiTunnelKeyInput = { key -> viewModel.send(SetupEvent.InputAiTunnelKey(key)) },
        onSaluteSpeechKeyInput = { key -> viewModel.send(SetupEvent.InputSaluteSpeechKey(key)) },
        onOpenProviderLink = { provider -> viewModel.send(SetupEvent.OpenProviderLink(provider)) },
        onChooseVoice = { viewModel.send(SetupEvent.ChooseVoice) },
        onProceed = { viewModel.send(SetupEvent.Proceed) },
        onResizeRequest = onResizeRequest,
    )
}

@Composable
fun SetupScreenContent(
    state: SetupState,
    onGigaChatKeyInput: (String) -> Unit,
    onQwenChatKeyInput: (String) -> Unit,
    onAiTunnelKeyInput: (String) -> Unit,
    onSaluteSpeechKeyInput: (String) -> Unit,
    onOpenProviderLink: (ApiKeyProvider) -> Unit,
    onChooseVoice: () -> Unit,
    onProceed: () -> Unit,
    onResizeRequest: (DpSize) -> Unit = {},
) {
    LaunchedEffect(Unit) { onResizeRequest(SetupWindowSize) }
    val hasNoKeys = state.configuredKeysCount == 0

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
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DraggableWindowArea {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = "Подключите API-ключи",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.glassColors.textPrimary
                        )
                    }
                }

                Text(
                    text = if (hasNoKeys) {
                        "Для старта добавьте хотя бы один ключ. Остальные можно подключить позже в настройках."
                    } else {
                        "Найдено ключей: ${state.configuredKeysCount}. Можно продолжать."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasNoKeys) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
                )

                if (hasNoKeys) {
                    KeyProvidersSection(onOpenProviderLink = onOpenProviderLink)
                }

                Spacer(Modifier.height(4.dp))

                LabeledTextField(
                    label = "GigaChat ключ",
                    value = state.gigaChatKey,
                    onValueChange = onGigaChatKeyInput,
                    modifier = Modifier.fillMaxWidth(),
                )

                LabeledTextField(
                    label = "Qwen ключ",
                    value = state.qwenChatKey,
                    onValueChange = onQwenChatKeyInput,
                    modifier = Modifier.fillMaxWidth(),
                )

                LabeledTextField(
                    label = "AI Tunnel ключ",
                    value = state.aiTunnelKey,
                    onValueChange = onAiTunnelKeyInput,
                    modifier = Modifier.fillMaxWidth(),
                )

                LabeledTextField(
                    label = "SaluteSpeech ключ (для голосовых команд)",
                    value = state.saluteSpeechKey,
                    onValueChange = onSaluteSpeechKeyInput,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "Голосовые команды используют SaluteSpeech API. " +
                        "Если хотите общаться голосом, обязательно добавьте этот ключ.",
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
                Button(
                    onClick = onProceed,
                    enabled = state.canProceed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (state.canProceed) "Открыть GigaDesk" else "Добавьте хотя бы один ключ",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        SetupBorderDragAreas(
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SetupBorderDragAreas(
    modifier: Modifier = Modifier,
    edgeThickness: Dp = 12.dp,
) {
    Box(modifier = modifier) {
        DraggableWindowArea {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(edgeThickness)
            )
        }
        DraggableWindowArea {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(edgeThickness)
            )
        }
        DraggableWindowArea {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(edgeThickness)
            )
        }
        DraggableWindowArea {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(edgeThickness)
            )
        }
    }
}

@Composable
private fun KeyProvidersSection(
    onOpenProviderLink: (ApiKeyProvider) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Где получить ключи",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.glassColors.textPrimary
        )

        ApiKeyProvider.entries.forEach { provider ->
            ProviderLinkCard(
                provider = provider,
                onOpen = { onOpenProviderLink(provider) }
            )
        }
    }
}

@Composable
private fun ProviderLinkCard(
    provider: ApiKeyProvider,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = provider.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.glassColors.textPrimary
            )
            Text(
                text = provider.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.78f)
            )
            Text(
                text = provider.details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.72f)
            )
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.glassColors.textPrimary
                )
            ) {
                Text(text = provider.url, style = MaterialTheme.typography.labelMedium)
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
                qwenChatKey = "",
                aiTunnelKey = "",
                saluteSpeechKey = "",
                configuredKeysCount = 0,
                canProceed = false
            ),
            onGigaChatKeyInput = {},
            onQwenChatKeyInput = {},
            onAiTunnelKeyInput = {},
            onSaluteSpeechKeyInput = {},
            onOpenProviderLink = {},
            onChooseVoice = {},
            onProceed = {},
            onResizeRequest = {},
        )
    }
}
