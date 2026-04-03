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
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import ru.souz.ui.AppTheme
import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.common.ApiKeyProvider
import ru.souz.ui.common.ConfirmDialog
import ru.souz.ui.common.DialogVariant
import ru.souz.ui.common.RegionProfileToggle
import ru.souz.ui.components.LabeledTextField
import ru.souz.ui.main.RealLiquidGlassCard
import ru.souz.ui.common.DraggableWindowArea
import ru.souz.ui.settings.SettingsUiColors
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*

private val SetupWindowSize = DpSize(width = 896.dp, height = 700.dp)
private val SetupControlHeight = 42.dp

private object SetupSpacing {
    val screenPaddingHorizontal = 20.dp
    val screenPaddingTop = 14.dp
    val screenPaddingBottom = 16.dp
    val sectionSpacing = 20.dp
    val elementSpacing = 12.dp
    val labelToFieldSpacing = 6.dp
}

private val SetupFieldBackground = SettingsUiColors.inputBackground
private val SetupBorder = SettingsUiColors.inputBorder
private val SetupStrongTextColor = SettingsUiColors.inputText
private val SetupLabelColor = SettingsUiColors.labelText
private val SetupHintColor = SettingsUiColors.labelTextSecondary

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

    SetupScreenContent(
        state = state,
        onUseEnglishVersionChange = { enabled -> viewModel.send(SetupEvent.InputUseEnglishVersion(enabled)) },
        onGigaChatKeyInput = { key -> viewModel.send(SetupEvent.InputGigaChatKey(key)) },
        onQwenChatKeyInput = { key -> viewModel.send(SetupEvent.InputQwenChatKey(key)) },
        onAiTunnelKeyInput = { key -> viewModel.send(SetupEvent.InputAiTunnelKey(key)) },
        onAnthropicKeyInput = { key -> viewModel.send(SetupEvent.InputAnthropicKey(key)) },
        onOpenAiKeyInput = { key -> viewModel.send(SetupEvent.InputOpenAiKey(key)) },
        onSaluteSpeechKeyInput = { key -> viewModel.send(SetupEvent.InputSaluteSpeechKey(key)) },
        onOpenProviderLink = { provider -> viewModel.send(SetupEvent.OpenProviderLink(provider)) },
        onChooseVoice = { viewModel.send(SetupEvent.ChooseVoice) },
        onDismissVoiceReminderDialog = { viewModel.send(SetupEvent.DismissVoiceReminderDialog) },
        onProceed = { viewModel.send(SetupEvent.Proceed) },
        onResizeRequest = onResizeRequest,
    )
}

@Composable
fun SetupScreenContent(
    state: SetupState,
    onUseEnglishVersionChange: (Boolean) -> Unit,
    onGigaChatKeyInput: (String) -> Unit,
    onQwenChatKeyInput: (String) -> Unit,
    onAiTunnelKeyInput: (String) -> Unit,
    onAnthropicKeyInput: (String) -> Unit,
    onOpenAiKeyInput: (String) -> Unit,
    onSaluteSpeechKeyInput: (String) -> Unit,
    onOpenProviderLink: (ApiKeyProvider) -> Unit,
    onChooseVoice: () -> Unit,
    onDismissVoiceReminderDialog: () -> Unit,
    onProceed: () -> Unit,
    onResizeRequest: (DpSize) -> Unit = {},
) {
    LaunchedEffect(Unit) { onResizeRequest(SetupWindowSize) }
    val hasNoKeys = state.configuredKeysCount == 0
    val supportsSaluteSpeech = ApiKeyField.SALUTE_SPEECH in state.availableApiKeyFields
    val supportsVoiceRecognition = state.supportsVoiceRecognitionApiKeys

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
                    .padding(top = 8.dp)
            ) {
                DraggableWindowArea {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = SetupSpacing.screenPaddingHorizontal,
                                end = SetupSpacing.screenPaddingHorizontal,
                                top = SetupSpacing.screenPaddingTop,
                                bottom = SetupSpacing.screenPaddingTop
                            ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.setup_title_keys),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = 32.sp,
                                lineHeight = 38.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = SetupStrongTextColor
                        )
                        Text(
                            text = if (hasNoKeys) {
                                when {
                                    state.supportsLocalInference && state.useEnglishVersion ->
                                        "No API key configured. Local inference is available."

                                    state.supportsLocalInference ->
                                        "API-ключ не настроен. Локальный inference доступен."

                                    else -> stringResource(Res.string.setup_hint_add_key)
                                }
                            } else {
                                stringResource(Res.string.setup_hint_keys_found).format(state.configuredKeysCount)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            color = if (hasNoKeys && !state.supportsLocalInference) {
                                MaterialTheme.colorScheme.error
                            } else {
                                SetupHintColor
                            }
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(SettingsUiColors.sidebarBorder)
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = SetupSpacing.screenPaddingHorizontal,
                            end = SetupSpacing.screenPaddingHorizontal,
                            top = SetupSpacing.screenPaddingTop,
                            bottom = SetupSpacing.screenPaddingBottom
                        ),
                    verticalArrangement = Arrangement.spacedBy(SetupSpacing.sectionSpacing)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(SetupSpacing.labelToFieldSpacing)) {
                        Text(
                            text = stringResource(Res.string.setting_language_profile_title),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = SetupLabelColor
                        )
                        Text(
                            text = stringResource(Res.string.setting_language_profile_desc),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            color = SetupHintColor
                        )
                        RegionProfileToggle(
                            useEnglishProfile = state.useEnglishVersion,
                            onProfileChange = onUseEnglishVersionChange,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(SetupSpacing.elementSpacing)) {
                        if (ApiKeyField.GIGA_CHAT in state.availableApiKeyFields) {
                            LabeledTextField(
                                label = stringResource(Res.string.label_key_gigachat),
                                value = state.gigaChatKey,
                                onValueChange = onGigaChatKeyInput,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (ApiKeyField.QWEN_CHAT in state.availableApiKeyFields) {
                            LabeledTextField(
                                label = stringResource(Res.string.label_key_qwen),
                                value = state.qwenChatKey,
                                onValueChange = onQwenChatKeyInput,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (ApiKeyField.AI_TUNNEL in state.availableApiKeyFields) {
                            LabeledTextField(
                                label = stringResource(Res.string.label_key_aitunnel),
                                value = state.aiTunnelKey,
                                onValueChange = onAiTunnelKeyInput,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (ApiKeyField.ANTHROPIC in state.availableApiKeyFields) {
                            LabeledTextField(
                                label = stringResource(Res.string.label_key_anthropic),
                                value = state.anthropicKey,
                                onValueChange = onAnthropicKeyInput,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (ApiKeyField.OPENAI in state.availableApiKeyFields) {
                            LabeledTextField(
                                label = stringResource(Res.string.label_key_openai),
                                value = state.openaiKey,
                                onValueChange = onOpenAiKeyInput,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (hasNoKeys) {
                        KeyProvidersSection(
                            availableProviders = state.availableApiKeyProviders,
                            onOpenProviderLink = onOpenProviderLink,
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

                    Column(verticalArrangement = Arrangement.spacedBy(SetupSpacing.labelToFieldSpacing)) {
                        Text(
                            text = if (supportsVoiceRecognition) {
                                stringResource(Res.string.setup_hint_voice_required)
                            } else {
                                stringResource(Res.string.setup_hint_voice_unavailable)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            color = SetupHintColor
                        )
                        OutlinedButton(
                            onClick = onChooseVoice,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(SetupControlHeight),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = SetupFieldBackground,
                                contentColor = SetupStrongTextColor
                            ),
                            border = BorderStroke(1.dp, SetupBorder),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.setup_btn_choose_voice),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = SetupStrongTextColor
                            )
                        }
                    }

                    Button(
                        onClick = onProceed,
                        enabled = state.canProceed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SetupControlHeight),
                        border = BorderStroke(1.dp, SettingsUiColors.buttonBorder),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SettingsUiColors.activeItemBackground,
                            contentColor = SettingsUiColors.activeItemText,
                            disabledContainerColor = SettingsUiColors.buttonBackground,
                            disabledContentColor = SettingsUiColors.labelTextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (state.canProceed) {
                                stringResource(Res.string.button_open_souz)
                            } else {
                                stringResource(Res.string.button_add_key_to_proceed)
                            },
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        SetupBorderDragAreas(
            modifier = Modifier.fillMaxSize()
        )
        if (state.showVoiceReminderDialog) {
            ConfirmDialog(
                isOpen = true,
                variant = DialogVariant.INFO,
                title = stringResource(Res.string.setup_voice_reminder_title),
                description = stringResource(Res.string.setup_voice_reminder_message),
                details = stringResource(Res.string.setup_voice_reminder_details),
                confirmText = stringResource(Res.string.setup_btn_choose_voice),
                cancelText = stringResource(Res.string.dialog_cancel),
                onConfirm = onChooseVoice,
                onDismiss = onDismissVoiceReminderDialog,
            )
        }
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
    availableProviders: List<ApiKeyProvider>,
    onOpenProviderLink: (ApiKeyProvider) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(Res.string.header_where_to_get_keys),
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 20.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = SetupStrongTextColor
        )

        availableProviders.forEach { provider ->
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
            .fillMaxWidth(),
        color = SetupFieldBackground,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SetupBorder)
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
                color = SetupStrongTextColor
            )
            Text(
                text = stringResource(provider.description),
                style = MaterialTheme.typography.bodySmall,
                color = SetupLabelColor
            )
            Text(
                text = stringResource(provider.details),
                style = MaterialTheme.typography.bodySmall,
                color = SetupHintColor
            )
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SetupControlHeight),
                border = BorderStroke(1.dp, SetupBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SetupFieldBackground,
                    contentColor = SetupStrongTextColor
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = provider.url,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = SetupStrongTextColor
                )
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
            onUseEnglishVersionChange = {},
            onGigaChatKeyInput = {},
            onQwenChatKeyInput = {},
            onAiTunnelKeyInput = {},
            onAnthropicKeyInput = {},
            onOpenAiKeyInput = {},
            onSaluteSpeechKeyInput = {},
            onOpenProviderLink = {},
            onChooseVoice = {},
            onDismissVoiceReminderDialog = {},
            onProceed = {},
            onResizeRequest = {},
        )
    }
}
