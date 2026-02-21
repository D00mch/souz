package ru.souz.ui.setup

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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import ru.souz.ui.AppTheme
import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.common.ApiKeyProvider
import ru.souz.ui.common.ApiKeysBuildProfile
import ru.souz.ui.components.LabeledTextField
import ru.souz.ui.glassColors
import ru.souz.ui.main.RealLiquidGlassCard
import ru.souz.ui.common.DraggableWindowArea
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*

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
        onAnthropicKeyInput = { key -> viewModel.send(SetupEvent.InputAnthropicKey(key)) },
        onOpenAiKeyInput = { key -> viewModel.send(SetupEvent.InputOpenAiKey(key)) },
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
    onAnthropicKeyInput: (String) -> Unit,
    onOpenAiKeyInput: (String) -> Unit,
    onSaluteSpeechKeyInput: (String) -> Unit,
    onOpenProviderLink: (ApiKeyProvider) -> Unit,
    onChooseVoice: () -> Unit,
    onProceed: () -> Unit,
    onResizeRequest: (DpSize) -> Unit = {},
) {
    LaunchedEffect(Unit) { onResizeRequest(SetupWindowSize) }
    val hasNoKeys = state.configuredKeysCount == 0
    val supportsSaluteSpeech = ApiKeysBuildProfile.hasField(ApiKeyField.SALUTE_SPEECH)
    val supportsVoiceRecognition = ApiKeysBuildProfile.supportsSpeechRecognition

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
                            text = stringResource(Res.string.setup_title_keys),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = if (hasNoKeys) {
                        stringResource(Res.string.setup_hint_add_key)
                    } else {
                        stringResource(Res.string.setup_hint_keys_found).format(state.configuredKeysCount)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasNoKeys) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
                )

                if (hasNoKeys) {
                    KeyProvidersSection(onOpenProviderLink = onOpenProviderLink)
                }

                Spacer(Modifier.height(4.dp))

                if (ApiKeysBuildProfile.hasField(ApiKeyField.GIGA_CHAT)) {
                    LabeledTextField(
                        label = stringResource(Res.string.label_key_gigachat),
                        value = state.gigaChatKey,
                        onValueChange = onGigaChatKeyInput,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (ApiKeysBuildProfile.hasField(ApiKeyField.QWEN_CHAT)) {
                    LabeledTextField(
                        label = stringResource(Res.string.label_key_qwen),
                        value = state.qwenChatKey,
                        onValueChange = onQwenChatKeyInput,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (ApiKeysBuildProfile.hasField(ApiKeyField.AI_TUNNEL)) {
                    LabeledTextField(
                        label = stringResource(Res.string.label_key_aitunnel),
                        value = state.aiTunnelKey,
                        onValueChange = onAiTunnelKeyInput,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (ApiKeysBuildProfile.hasField(ApiKeyField.ANTHROPIC)) {
                    LabeledTextField(
                        label = stringResource(Res.string.label_key_anthropic),
                        value = state.anthropicKey,
                        onValueChange = onAnthropicKeyInput,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (ApiKeysBuildProfile.hasField(ApiKeyField.OPENAI)) {
                    LabeledTextField(
                        label = stringResource(Res.string.label_key_openai),
                        value = state.openaiKey,
                        onValueChange = onOpenAiKeyInput,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (supportsSaluteSpeech) {
                    LabeledTextField(
                        label = stringResource(Res.string.label_key_salutespeech),
                        value = state.saluteSpeechKey,
                        onValueChange = onSaluteSpeechKeyInput,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = if (supportsVoiceRecognition) {
                        stringResource(Res.string.setup_hint_voice_required)
                    } else {
                        stringResource(Res.string.setup_hint_voice_unavailable)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )

                OutlinedButton(
                    onClick = onChooseVoice,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(Res.string.setup_btn_choose_voice),
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
                        text = if (state.canProceed) stringResource(Res.string.button_open_souz) else stringResource(Res.string.button_add_key_to_proceed),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 16.sp,
                        ),
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
            text = stringResource(Res.string.setup_title_keys),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.glassColors.textPrimary
        )

        ApiKeysBuildProfile.providers.forEach { provider ->
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
                text = stringResource(provider.title),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )
            Text(
                text = stringResource(provider.description),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            Text(
                text = stringResource(provider.details),
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
                anthropicKey = "",
                openaiKey = "",
                saluteSpeechKey = "",
                configuredKeysCount = 0,
                canProceed = false
            ),
            onGigaChatKeyInput = {},
            onQwenChatKeyInput = {},
            onAiTunnelKeyInput = {},
            onAnthropicKeyInput = {},
            onOpenAiKeyInput = {},
            onSaluteSpeechKeyInput = {},
            onOpenProviderLink = {},
            onChooseVoice = {},
            onProceed = {},
            onResizeRequest = {},
        )
    }
}
