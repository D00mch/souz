package ru.souz.ui.settings

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.souz.agent.AgentId
import ru.souz.giga.EmbeddingsModel
import ru.souz.giga.GigaModel
import ru.souz.giga.GigaResponse
import ru.souz.giga.VoiceRecognitionModel
import ru.souz.ui.AppTheme
import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.common.ApiKeyProvider
import ru.souz.ui.common.ConfirmDialog
import ru.souz.ui.common.ConfirmDialogType
import ru.souz.ui.components.LabeledTextField
import ru.souz.ui.glassColors
import ru.souz.ui.main.RealLiquidGlassCard
import org.jetbrains.compose.resources.stringResource
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*

private val SettingsFieldBackground = SettingsUiColors.inputBackground
private val SettingsButtonBackground = SettingsUiColors.buttonBackground
private val SettingsDefaultBorder = SettingsUiColors.inputBorder
private val SettingsStrongTextColor = SettingsUiColors.inputText
private val SettingsDescriptionColor = SettingsUiColors.inactiveItemText
private val SettingsHintColor = SettingsUiColors.labelTextSecondary
private val SettingsLabelColor = SettingsUiColors.labelText
private val SettingsAccent = SettingsUiColors.refreshButtonText
private val SettingsAccentBackground = SettingsUiColors.toggleActiveBackground
private val SettingsAccentActiveBackground = SettingsUiColors.activeItemBackground
private val SettingsContentGradientTop = Color(0x0F000000)
private val SettingsContentGradientMiddle = Color(0x07000000)
private val SettingsContentGradientBottom = Color(0x0F000000)
private val SettingsSendLogsNormalGradientStart = Color(0x14FFFFFF)
private val SettingsSendLogsNormalGradientEnd = Color(0x05FFFFFF)
private val SettingsSendLogsHoverGradientStart = Color(0x26FFFFFF)
private val SettingsSendLogsHoverGradientEnd = Color(0x0DFFFFFF)
private val SettingsSendLogsLoadingBackground = Color(0x26000000)
private val SettingsSendLogsBorder = Color(0x33FFFFFF)
private val SettingsSendLogsHoverBorder = Color(0x4DFFFFFF)
private val SettingsSendLogsLoadingBorder = Color(0x14FFFFFF)
private val SettingsSendLogsText = Color(0xE5FFFFFF)
private val SettingsSendLogsLoadingText = Color(0x4DFFFFFF)
private val SettingsControlHeight = 42.dp

private object SettingsSpacing {
    val screenPaddingHorizontal = 20.dp
    val screenPaddingTop = 14.dp
    val screenPaddingBottom = 16.dp
    val sectionSpacing = 20.dp
    val elementSpacing = 12.dp
    val labelToFieldSpacing = 6.dp
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsHoverTooltip(
    text: String,
    delayMillis: Int = 300,
    content: @Composable () -> Unit
) {
    if (text.isBlank()) {
        content()
        return
    }
    TooltipArea(
        delayMillis = delayMillis,
        tooltip = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE61A1C20))
                    .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    color = Color(0xE6FFFFFF)
                )
            }
        }
    ) {
        content()
    }
}

@Composable
private fun SettingsGroupDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.1f),
                        Color.Transparent
                    )
                )
            )
    )
}

@Composable
private fun SettingsCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 18.dp else 2.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "settingsSwitchThumbOffset"
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (checked) SettingsUiColors.switchBackgroundChecked else SettingsUiColors.switchBackground,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "settingsSwitchBackground"
    )

    Box(
        modifier = Modifier
            .width(36.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset, y = 2.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(SettingsUiColors.switchThumb)
        )
    }
}

@Composable
private fun SettingsSectionScreen(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SettingsContentGradientTop,
                        SettingsContentGradientMiddle,
                        SettingsContentGradientBottom
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp, top = 2.dp, bottom = 14.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(Res.string.button_back),
                        tint = SettingsUiColors.inactiveItemText,
                        modifier = Modifier.size(24.dp)
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
                        start = SettingsSpacing.screenPaddingHorizontal,
                        end = SettingsSpacing.screenPaddingHorizontal,
                        top = SettingsSpacing.screenPaddingTop,
                        bottom = SettingsSpacing.screenPaddingBottom
                    ),
                verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionSpacing)
            ) {
            content()
            }
        }
    }
}

@Composable
fun ModelsSettingsContent(
    state: SettingsState,
    onModelChange: (GigaModel) -> Unit,
    onEmbeddingsModelChange: (EmbeddingsModel) -> Unit,
    onVoiceRecognitionModelChange: (VoiceRecognitionModel) -> Unit,
    onTemperatureInput: (String) -> Unit,
    onRequestTimeoutMillisChange: (String) -> Unit,
    onContextSizeInput: (String) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onSystemPromptReset: () -> Unit,
    onRefreshBalance: () -> Unit,
    onClose: () -> Unit
) {
    SettingsSectionScreen(
        title = stringResource(Res.string.settings_section_models),
        subtitle = stringResource(Res.string.settings_models_subtitle),
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)) {
            ModelDropdown(
                selectedModel = state.gigaModel,
                availableModels = state.availableLlmModels,
                onModelSelected = onModelChange,
            )

            if (state.availableEmbeddingsModels.isNotEmpty()) {
                EmbeddingsModelDropdown(
                    selectedModel = state.embeddingsModel,
                    availableModels = state.availableEmbeddingsModels,
                    onModelSelected = onEmbeddingsModelChange,
                )
            }

            if (state.availableVoiceRecognitionModels.isNotEmpty()) {
                VoiceRecognitionModelDropdown(
                    selectedModel = state.voiceRecognitionModel,
                    availableModels = state.availableVoiceRecognitionModels,
                    onModelSelected = onVoiceRecognitionModelChange,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)
                ) {
                    LabeledTextField(
                        label = stringResource(Res.string.label_temperature),
                        value = state.temperatureInput,
                        onValueChange = onTemperatureInput,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)
                ) {
                    LabeledTextField(
                        label = stringResource(Res.string.label_timeout),
                        value = state.requestTimeoutInput,
                        onValueChange = onRequestTimeoutMillisChange,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                LabeledTextField(
                    label = stringResource(Res.string.label_context_size),
                    value = state.contextSizeInput,
                    onValueChange = onContextSizeInput,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SettingsGroupDivider()

        Column(
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)
        ) {
            val resetButtonInteraction = remember { MutableInteractionSource() }
            val isResetHovered by resetButtonInteraction.collectIsHoveredAsState()
            val resetButtonScale by animateFloatAsState(
                targetValue = if (isResetHovered) 1.05f else 1f,
                animationSpec = tween(durationMillis = 150),
                label = "resetButtonScale"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                 Text(
                    text = stringResource(Res.string.label_system_prompt),
                    style = MaterialTheme.typography.titleMedium,
                    color = SettingsStrongTextColor
                )
                TextButton(
                    onClick = onSystemPromptReset,
                    modifier = Modifier.graphicsLayer {
                        scaleX = resetButtonScale
                        scaleY = resetButtonScale
                    },
                    interactionSource = resetButtonInteraction,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = SettingsAccent,
                        containerColor = SettingsAccentBackground,
                        disabledContentColor = Color.White.copy(alpha = 0.3f),
                        disabledContainerColor = Color.Transparent
                    )
                ) {
                    Text(
                        text = stringResource(Res.string.button_reset),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            LabeledTextField(
                label = "",
                value = state.systemPrompt,
                onValueChange = onSystemPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                singleLine = false,
            )
        }

        TokensBalanceSection(
            isLoading = state.isBalanceLoading,
            balance = state.balance,
            error = state.balanceError,
            onRefreshBalance = onRefreshBalance,
        )
    }
}

@Composable
fun GeneralSettingsContent(
    state: SettingsState,
    onDefaultCalendarChange: (String?) -> Unit,
    onUseStreamingChange: (Boolean) -> Unit,
    onNotificationSoundEnabledChange: (Boolean) -> Unit,
    onUseEnglishVersionChange: (Boolean) -> Unit,
    onChooseVoice: () -> Unit,
    onVoiceSpeedInput: (String) -> Unit,
    onMcpServersJsonInput: (String) -> Unit,
    onClose: () -> Unit
) {
    SettingsSectionScreen(
        title = stringResource(Res.string.settings_section_general),
        subtitle = stringResource(Res.string.settings_general_subtitle),
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)) {
            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                Text(
                    text = stringResource(Res.string.setting_language_profile_title),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = SettingsLabelColor
                )
                SettingsHoverTooltip(text = stringResource(Res.string.setting_language_profile_desc)) {
                    SettingsRegionProfileToggle(
                        useEnglishProfile = state.useEnglishVersion,
                        onProfileChange = onUseEnglishVersionChange,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            CalendarDropdown(
                selectedCalendar = state.defaultCalendar,
                availableCalendars = state.availableCalendars,
                isLoading = state.isLoadingCalendars,
                onCalendarSelected = onDefaultCalendarChange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsSwitchCard(
                    title = stringResource(Res.string.setting_streaming_title),
                    description = stringResource(Res.string.setting_streaming_desc),
                    checked = state.useStreaming,
                    onCheckedChange = onUseStreamingChange,
                    modifier = Modifier.weight(1f)
                )
                SettingsSwitchCard(
                    title = stringResource(Res.string.setting_notification_sound_title),
                    description = stringResource(Res.string.setting_notification_sound_desc),
                    checked = state.notificationSoundEnabled,
                    onCheckedChange = onNotificationSoundEnabledChange,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                SettingsHoverTooltip(text = stringResource(Res.string.hint_voice_speed).format(state.voiceSpeed)) {
                    LabeledTextField(
                        label = stringResource(Res.string.label_voice_speed),
                        value = state.voiceSpeedInput,
                        onValueChange = onVoiceSpeedInput,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                Text(
                    text = stringResource(Res.string.label_voice_selection),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = SettingsLabelColor
                )
                SettingsHoverTooltip(
                    text = if (state.supportsVoiceRecognitionApiKeys) {
                        stringResource(Res.string.setup_hint_voice_required)
                    } else {
                        stringResource(Res.string.setup_hint_voice_unavailable)
                    }
                ) {
                    OutlinedButton(
                        onClick = onChooseVoice,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SettingsControlHeight),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SettingsFieldBackground,
                            contentColor = SettingsStrongTextColor
                        ),
                        border = BorderStroke(1.dp, SettingsDefaultBorder),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.button_select_voice),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = SettingsStrongTextColor
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                Text(
                    text = stringResource(Res.string.label_mcp_servers),
                     style = MaterialTheme.typography.labelMedium.copy(
                         fontSize = 12.sp,
                         lineHeight = 16.sp,
                         fontWeight = FontWeight.Medium
                     ),
                     color = SettingsLabelColor
                )
                LabeledTextField(
                    label = "",
                    value = state.mcpServersJson,
                    onValueChange = onMcpServersJsonInput,
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    singleLine = false,
                )
            }
        }
    }
}

@Composable
fun KeysSettingsContent(
    state: SettingsState,
    onGigaChatKeyInput: (String) -> Unit,
    onQwenChatKeyInput: (String) -> Unit,
    onAiTunnelKeyInput: (String) -> Unit,
    onAnthropicKeyInput: (String) -> Unit,
    onOpenAiKeyInput: (String) -> Unit,
    onSaluteSpeechKeyInput: (String) -> Unit,
    onOpenProviderLink: (ApiKeyProvider) -> Unit,
    onClose: () -> Unit
) {
    val supportsSaluteSpeech = ApiKeyField.SALUTE_SPEECH in state.availableApiKeyFields
    val supportsVoiceRecognition = state.supportsVoiceRecognitionApiKeys
    val keysHintChat = if (ApiKeyField.GIGA_CHAT in state.availableApiKeyFields) {
        Res.string.keys_hint_chat_ru_build
    } else {
        Res.string.keys_hint_chat_en_build
    }
    val keysHintVoice = if (supportsVoiceRecognition) {
        Res.string.keys_hint_voice_required
    } else {
        Res.string.keys_hint_voice_unavailable
    }
    SettingsSectionScreen(
        title = stringResource(Res.string.settings_section_keys),
        subtitle = stringResource(Res.string.settings_keys_subtitle),
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SettingsFieldBackground,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SettingsDefaultBorder),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.keys_configured_count).format(state.configuredKeysCount),
                        style = MaterialTheme.typography.titleSmall,
                        color = SettingsStrongTextColor
                    )
                    Text(
                        text = stringResource(keysHintChat),
                        style = MaterialTheme.typography.bodySmall,
                        color = SettingsHintColor
                    )
                    Text(
                        text = stringResource(keysHintVoice),
                        style = MaterialTheme.typography.bodySmall,
                        color = SettingsHintColor
                    )
                }
            }


            if (ApiKeyField.GIGA_CHAT in state.availableApiKeyFields) {
                LabeledTextField(
                    label = stringResource(Res.string.label_key_gigachat),
                    value = state.gigaChatKey,
                    onValueChange = onGigaChatKeyInput,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (ApiKeyField.QWEN_CHAT in state.availableApiKeyFields) {
                LabeledTextField(
                    label = stringResource(Res.string.label_key_qwen),
                    value = state.qwenChatKey,
                    onValueChange = onQwenChatKeyInput,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (ApiKeyField.AI_TUNNEL in state.availableApiKeyFields) {
                LabeledTextField(
                    label = stringResource(Res.string.label_key_aitunnel),
                    value = state.aiTunnelKey,
                    onValueChange = onAiTunnelKeyInput,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (ApiKeyField.ANTHROPIC in state.availableApiKeyFields) {
                LabeledTextField(
                    label = stringResource(Res.string.label_key_anthropic),
                    value = state.anthropicKey,
                    onValueChange = onAnthropicKeyInput,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (ApiKeyField.OPENAI in state.availableApiKeyFields) {
                LabeledTextField(
                    label = stringResource(Res.string.label_key_openai),
                    value = state.openaiKey,
                    onValueChange = onOpenAiKeyInput,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (supportsSaluteSpeech) {
            SettingsGroupDivider()

            LabeledTextField(
                label = stringResource(Res.string.label_key_salutespeech),
                value = state.saluteSpeechKey,
                onValueChange = onSaluteSpeechKeyInput,
                modifier = Modifier.fillMaxWidth()
            )
        }

        SettingsGroupDivider()

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(Res.string.header_where_to_get_keys),
                style = MaterialTheme.typography.titleMedium,
                color = SettingsStrongTextColor
            )
            state.availableApiKeyProviders.forEach { provider ->
                ProviderLinkCard(
                    provider = provider,
                    onOpen = { onOpenProviderLink(provider) }
                )
            }
        }
    }
}

@Composable
private fun ProviderLinkCard(
    provider: ApiKeyProvider,
    onOpen: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SettingsFieldBackground,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SettingsDefaultBorder),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(provider.title),
                style = MaterialTheme.typography.titleSmall,
                color = SettingsStrongTextColor
            )
            Text(
                text = stringResource(provider.description),
                style = MaterialTheme.typography.bodySmall,
                color = SettingsHintColor
            )
            Text(
                text = stringResource(provider.details),
                style = MaterialTheme.typography.bodySmall,
                color = SettingsHintColor
            )
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, SettingsDefaultBorder),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = provider.url,
                    style = MaterialTheme.typography.labelMedium,
                    color = SettingsStrongTextColor
                )
            }
        }
    }
}

@Composable
fun FunctionsSettingsContent(
    state: SettingsState,
    onUseFewShotExamplesChange: (Boolean) -> Unit,
    onAgentSelected: (AgentId) -> Unit,
    onConfirmAgentSwitch: () -> Unit,
    onCancelAgentSwitch: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenTelegramSettings: () -> Unit,
    onClose: () -> Unit
) {
    SettingsSectionScreen(
        title = stringResource(Res.string.settings_section_functions),
        subtitle = stringResource(Res.string.settings_functions_subtitle),
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)) {
            AgentDropdown(
                selectedAgent = state.activeAgentId,
                availableAgents = state.availableAgents,
                onAgentSelected = onAgentSelected,
            )

            SettingsRow(
                title = stringResource(Res.string.setting_fewshot_title),
                description = stringResource(Res.string.setting_fewshot_desc),
                content = {
                    SettingsCheckbox(
                        checked = state.useFewShotExamples,
                        onCheckedChange = onUseFewShotExamplesChange
                    )
                }
            )

            Button(
                onClick = onOpenTools,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SettingsControlHeight),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SettingsButtonBackground,
                    contentColor = SettingsStrongTextColor
                ),
                border = BorderStroke(1.dp, SettingsDefaultBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(Res.string.button_configure_tools),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = SettingsStrongTextColor,
                )
            }

            if (state.isTelegramSupported) {
                val telegramStatus = when (state.telegramAuthStep) {
                    TelegramAuthStepUi.CONNECTED -> stringResource(Res.string.telegram_status_connected_format).format(state.telegramActiveSessionPhone ?: stringResource(Res.string.telegram_session_default))
                    TelegramAuthStepUi.LOGGING_OUT -> stringResource(Res.string.telegram_status_logging_out)
                    TelegramAuthStepUi.INITIALIZING -> stringResource(Res.string.telegram_status_initializing)
                    TelegramAuthStepUi.PHONE,
                    TelegramAuthStepUi.CODE,
                    TelegramAuthStepUi.PASSWORD,
                    TelegramAuthStepUi.ERROR -> stringResource(Res.string.telegram_status_not_connected)
                }

                Button(
                    onClick = onOpenTelegramSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SettingsButtonBackground,
                        contentColor = SettingsStrongTextColor
                    ),
                    border = BorderStroke(1.dp, SettingsDefaultBorder),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.button_configure_telegram),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = SettingsStrongTextColor,
                        )
                        Text(
                            text = telegramStatus,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            color = if (state.telegramAuthStep == TelegramAuthStepUi.CONNECTED) {
                                SettingsUiColors.hoverItemText
                            } else {
                                SettingsHintColor
                            }
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SettingsControlHeight),
                    color = SettingsButtonBackground,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SettingsDefaultBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.button_configure_telegram),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = SettingsStrongTextColor,
                        )
                        Text(
                            text = stringResource(Res.string.telegram_error_macos_15_required),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (state.showAgentSwitchConfirmation && state.pendingAgentId != null) {
        ConfirmDialog(
            type = ConfirmDialogType.WARNING,
            title = stringResource(Res.string.agent_switch_dialog_title),
            message = stringResource(Res.string.agent_switch_dialog_message),
            confirmText = stringResource(Res.string.agent_switch_dialog_confirm),
            cancelText = stringResource(Res.string.agent_switch_dialog_cancel),
            onConfirm = onConfirmAgentSwitch,
            onDismiss = onCancelAgentSwitch,
        )
    }
}

@Composable
fun SecuritySettingsContent(
    state: SettingsState,
    onSafeModeChange: (Boolean) -> Unit,
    onOpenFoldersManagement: () -> Unit,
    onClose: () -> Unit
) {
    SettingsSectionScreen(
        title = stringResource(Res.string.settings_section_security),
        subtitle = stringResource(Res.string.settings_security_subtitle),
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)) {
            SettingsRow(
                title = stringResource(Res.string.setting_safemode_title),
                description = stringResource(Res.string.setting_safemode_desc),
                content = {
                    SettingsCheckbox(
                        checked = state.safeModeEnabled,
                        onCheckedChange = onSafeModeChange
                    )
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                Text(
                    text = stringResource(Res.string.label_forbidden_folders),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = SettingsStrongTextColor
                )
                SettingsHoverTooltip(text = stringResource(Res.string.hint_forbidden_folders)) {
                    Button(
                        onClick = onOpenFoldersManagement,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SettingsControlHeight),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SettingsButtonBackground,
                            contentColor = SettingsStrongTextColor
                        ),
                        border = BorderStroke(1.dp, SettingsDefaultBorder),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.button_manage_forbidden_folders),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }

        }
    }
}

@Composable
fun TelegramSettingsScreen(
    state: SettingsState,
    onClose: () -> Unit,
    onStartWork: () -> Unit,
    onCreateControlBot: () -> Unit,
    onDisconnectControlBot: () -> Unit,
    onConfirmDisconnectControlBot: () -> Unit,
    onCancelDisconnectControlBot: () -> Unit,
) {
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = isFocused
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(Res.string.button_back),
                            tint = SettingsUiColors.inactiveItemText,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(SettingsUiColors.sidebarBorder)
                )

                if (!state.isTelegramSupported) {
                    Text(
                        text = stringResource(Res.string.telegram_error_macos_15_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    TelegramLoginContent(
                        state = state,
                        onStartWork = onStartWork,
                        onCreateControlBot = onCreateControlBot,
                        onDisconnectControlBot = onDisconnectControlBot,
                    )
                }
            }
        }
    }

    if (state.isTelegramSupported && state.showBotDeleteConfirmation && state.botNameToDelete != null) {
        ru.souz.ui.common.ConfirmDialog(
            type = ru.souz.ui.common.ConfirmDialogType.WARNING,
            title = stringResource(Res.string.telegram_dialog_delete_title),
            message = stringResource(Res.string.telegram_dialog_delete_text).format(state.botNameToDelete),
            confirmText = stringResource(Res.string.telegram_dialog_delete_confirm),
            cancelText = stringResource(Res.string.telegram_dialog_delete_cancel),
            onConfirm = onConfirmDisconnectControlBot,
            onDismiss = onCancelDisconnectControlBot
        )
    }
}

@Composable
fun SupportSettingsContent(
    state: SettingsState,
    onSupportEmailInput: (String) -> Unit,
    onSendLogs: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    clipboardManager: ClipboardManager,
    onShowSnack: (String) -> Unit,
    onOpenGraphSessions: () -> Unit,
    onClose: () -> Unit
) {
    SettingsSectionScreen(
        title = stringResource(Res.string.settings_section_support),
        subtitle = stringResource(Res.string.settings_support_subtitle),
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)) {
            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                Text(
                    text = stringResource(Res.string.label_privacy_policy),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = SettingsStrongTextColor
                )
                SettingsHoverTooltip(text = stringResource(Res.string.hint_privacy_policy)) {
                    OutlinedButton(
                        onClick = onOpenPrivacyPolicy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SettingsControlHeight),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SettingsButtonBackground,
                            contentColor = SettingsStrongTextColor
                        ),
                        border = BorderStroke(1.dp, SettingsDefaultBorder),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.button_open_privacy_policy),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = SettingsStrongTextColor,
                        )
                    }
                }
            }

            SettingsGroupDivider()

            Button(
                onClick = onOpenGraphSessions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SettingsControlHeight),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SettingsButtonBackground,
                    contentColor = SettingsStrongTextColor
                ),
                border = BorderStroke(1.dp, SettingsDefaultBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(Res.string.button_view_sessions),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = SettingsStrongTextColor,
                )
            }

            SettingsGroupDivider()

            LogsView(state, onSupportEmailInput, onSendLogs, clipboardManager, onShowSnack)
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    SettingsHoverTooltip(text = description) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = SettingsStrongTextColor
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsRegionProfileToggle(
    useEnglishProfile: Boolean,
    onProfileChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SettingsFieldBackground)
            .border(1.dp, SettingsDefaultBorder, RoundedCornerShape(10.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRegionSegment(
            text = "Русский (RU)",
            selected = !useEnglishProfile,
            onClick = { if (useEnglishProfile) onProfileChange(false) },
            modifier = Modifier.weight(1f)
        )
        SettingsRegionSegment(
            text = "English (EN)",
            selected = useEnglishProfile,
            onClick = { if (!useEnglishProfile) onProfileChange(true) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SettingsSwitchCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsHoverTooltip(text = description) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(SettingsFieldBackground)
                .border(1.dp, SettingsDefaultBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = SettingsLabelColor
            )
            SettingsCheckbox(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun SettingsRegionSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SettingsUiColors.toggleActiveBackground else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium
            ),
            color = if (selected) SettingsUiColors.toggleActiveText else SettingsUiColors.toggleInactiveText,
        )
    }
}

@Composable
fun LogsView(
    state: SettingsState,
    onSupportEmailInput: (String) -> Unit,
    onSendLogs: () -> Unit,
    clipboardManager: ClipboardManager,
    onShowSnack: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)
    ) {
        LabeledTextField(
            label = stringResource(Res.string.label_support_email),
            value = state.supportEmail,
            onValueChange = onSupportEmailInput,
            modifier = Modifier.fillMaxWidth()
        )
        SendLogsButton(
            isSending = state.isSendingLogs,
            onSendLogs = onSendLogs
        )
        val snackMessage = stringResource(Res.string.snack_logs_path_copied)
        state.sendLogsMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.glassColors.textPrimary,
                textAlign = TextAlign.Start,
                modifier = Modifier.clickable(enabled = state.sendLogsPath != null) {
                    state.sendLogsPath?.let { path ->
                        clipboardManager.setText(AnnotatedString(path))
                        onShowSnack(snackMessage)
                    }
                }
            )
        }
    }
}

@Composable
private fun SendLogsButton(
    isSending: Boolean,
    onSendLogs: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val gradientStart by animateColorAsState(
        targetValue = when {
            isSending -> SettingsSendLogsLoadingBackground
            isHovered -> SettingsSendLogsHoverGradientStart
            else -> SettingsSendLogsNormalGradientStart
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "sendLogsGradientStart"
    )
    val gradientEnd by animateColorAsState(
        targetValue = when {
            isSending -> SettingsSendLogsLoadingBackground
            isHovered -> SettingsSendLogsHoverGradientEnd
            else -> SettingsSendLogsNormalGradientEnd
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "sendLogsGradientEnd"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSending -> SettingsSendLogsLoadingBorder
            isHovered -> SettingsSendLogsHoverBorder
            else -> SettingsSendLogsBorder
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "sendLogsBorderColor"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            isSending -> SettingsSendLogsLoadingText
            isHovered -> Color.White
            else -> SettingsSendLogsText
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "sendLogsTextColor"
    )
    val buttonScale by animateFloatAsState(
        targetValue = if (!isSending && isHovered) 1.01f else 1f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "sendLogsScale"
    )
    val buttonAlpha by animateFloatAsState(
        targetValue = if (isSending) 0.6f else 1f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "sendLogsAlpha"
    )

    val rotationTransition = rememberInfiniteTransition(label = "sendLogsRotationTransition")
    val rotation by rotationTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sendLogsRotation"
    )

    Button(
        onClick = onSendLogs,
        enabled = !isSending,
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingsControlHeight),
        interactionSource = interactionSource,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.Transparent
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                    alpha = buttonAlpha
                }
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(gradientStart, gradientEnd)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isSending) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = rotation }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.button_sending_logs),
                        color = textColor,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            } else {
                Text(
                    text = stringResource(Res.string.button_send_logs),
                    color = textColor,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

@Composable
fun CalendarDropdown(
    selectedCalendar: String?,
    availableCalendars: List<String>,
    isLoading: Boolean,
    onCalendarSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.label_default_calendar),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium
            ),
            color = SettingsLabelColor,
        )

        Box {
            SettingsHoverTooltip(text = stringResource(Res.string.hint_calendar_usage)) {
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth().height(SettingsControlHeight),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SettingsFieldBackground,
                        contentColor = SettingsStrongTextColor
                    ),
                    border = BorderStroke(1.dp, SettingsDefaultBorder),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                isLoading -> stringResource(Res.string.status_loading_calendars)
                                selectedCalendar.isNullOrBlank() -> stringResource(Res.string.calendar_not_selected)
                                else -> selectedCalendar
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            color = SettingsStrongTextColor
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = stringResource(Res.string.content_desc_choose_calendar),
                            tint = SettingsStrongTextColor
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .background(SettingsFieldBackground, RoundedCornerShape(12.dp))
                    .border(1.dp, SettingsDefaultBorder, RoundedCornerShape(12.dp))
            ) {
                DropdownMenuItem(
                    text = {
                            Text(
                                text = stringResource(Res.string.calendar_not_selected_short),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                ),
                                color = SettingsStrongTextColor
                            )
                    },
                    onClick = {
                        onCalendarSelected(null)
                        expanded = false
                    }
                )

                if (availableCalendars.isEmpty() && !isLoading) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(Res.string.calendar_no_available),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                ),
                                color = SettingsDescriptionColor
                            )
                        },
                        enabled = false,
                        onClick = {}
                    )
                }

                availableCalendars.forEach { calendarName ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = calendarName,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                ),
                                color = SettingsStrongTextColor
                            )
                        },
                        onClick = {
                            onCalendarSelected(calendarName)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TokensBalanceSection(
    isLoading: Boolean,
    balance: List<GigaResponse.BalanceItem>,
    error: String?,
    onRefreshBalance: () -> Unit,
) {
    val refreshButtonInteraction = remember { MutableInteractionSource() }
    val isRefreshHovered by refreshButtonInteraction.collectIsHoveredAsState()
    val refreshScale by animateFloatAsState(
        targetValue = if (isRefreshHovered) 1.10f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "refreshButtonScale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = SettingsFieldBackground,
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = 1.dp,
                color = SettingsDefaultBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = SettingsUiColors.refreshButtonText
                )
                Text(
                    text = stringResource(Res.string.label_tokens_balance),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SettingsStrongTextColor,
                )
            }

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = refreshScale
                        scaleY = refreshScale
                    }
                    .clip(CircleShape)
                    .background(if (isRefreshHovered) SettingsUiColors.buttonHoverBackground else SettingsUiColors.buttonBackground)
                    .border(1.dp, if (isRefreshHovered) SettingsUiColors.buttonHoverBorder else SettingsUiColors.buttonBorder, CircleShape)
                    .clickable(
                        enabled = !isLoading,
                        interactionSource = refreshButtonInteraction,
                        indication = null,
                        onClick = onRefreshBalance
                    )
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = SettingsUiColors.refreshButtonTextHover,
                        strokeWidth = 1.8.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = if (isRefreshHovered) SettingsUiColors.refreshButtonTextHover else SettingsUiColors.refreshButtonText,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } 
        }

        when {
            isLoading -> Text(
                text = stringResource(Res.string.status_checking_balance),
                style = MaterialTheme.typography.bodyMedium,
                color = SettingsStrongTextColor,
            )

            error != null -> Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            balance.isEmpty() -> Text(
                text = stringResource(Res.string.status_balance_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = SettingsDescriptionColor,
            )

            else -> Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                balance.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.usage,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = SettingsStrongTextColor.copy(alpha = 0.9f),
                        )
                        Text(
                            text = item.value.toString(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            color = SettingsStrongTextColor.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AgentDropdown(
    selectedAgent: AgentId,
    availableAgents: List<AgentId>,
    onAgentSelected: (AgentId) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDescriptionRes = selectedAgent.descriptionRes()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.label_agent),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium
            ),
            color = SettingsLabelColor,
        )
        Box {
            SettingsHoverTooltip(text = stringResource(selectedDescriptionRes)) {
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    enabled = availableAgents.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SettingsControlHeight),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SettingsFieldBackground,
                        contentColor = SettingsStrongTextColor
                    ),
                    border = BorderStroke(1.dp, SettingsDefaultBorder),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(selectedAgent.titleRes()),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            color = SettingsStrongTextColor
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = stringResource(Res.string.content_desc_select_agent),
                            tint = SettingsStrongTextColor
                        )
                    }
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .background(SettingsFieldBackground, RoundedCornerShape(12.dp))
                    .border(1.dp, SettingsDefaultBorder, RoundedCornerShape(12.dp))
            ) {
                availableAgents.forEach { agentId ->
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringResource(agentId.titleRes()),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = SettingsStrongTextColor
                                )
                                Text(
                                    text = stringResource(agentId.descriptionRes()),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    ),
                                    color = SettingsHintColor,
                                )
                            }
                        },
                        onClick = {
                            onAgentSelected(agentId)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun AgentId.titleRes() = when (this) {
    AgentId.GRAPH -> Res.string.agent_option_graph_title
    AgentId.LUA_GRAPH -> Res.string.agent_option_lua_title
}

private fun AgentId.descriptionRes() = when (this) {
    AgentId.GRAPH -> Res.string.agent_option_graph_description
    AgentId.LUA_GRAPH -> Res.string.agent_option_lua_description
}

@Composable
fun ModelDropdown(
    selectedModel: GigaModel,
    availableModels: List<GigaModel>,
    onModelSelected: (GigaModel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.label_model),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium
            ),
            color = SettingsLabelColor,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                enabled = availableModels.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(SettingsControlHeight),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SettingsFieldBackground,
                    contentColor = SettingsStrongTextColor
                ),
                border = BorderStroke(1.dp, SettingsDefaultBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedModel.displayName} (${selectedModel.alias})",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
                        color = SettingsStrongTextColor
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Выбрать модель",
                        tint = SettingsStrongTextColor
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .background(SettingsFieldBackground, RoundedCornerShape(12.dp))
                    .border(1.dp, SettingsDefaultBorder, RoundedCornerShape(12.dp))
            ) {
                availableModels.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${model.displayName} (${model.alias})",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                ),
                                color = SettingsStrongTextColor
                            )
                        },
                        onClick = {
                            onModelSelected(model)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun EmbeddingsModelDropdown(
    selectedModel: EmbeddingsModel,
    availableModels: List<EmbeddingsModel>,
    onModelSelected: (EmbeddingsModel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.label_embeddings_model),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium
            ),
            color = SettingsLabelColor,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                enabled = availableModels.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(SettingsControlHeight),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SettingsFieldBackground,
                    contentColor = SettingsStrongTextColor
                ),
                border = BorderStroke(1.dp, SettingsDefaultBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedModel.displayName,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
                        color = SettingsStrongTextColor
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Выбрать модель эмбеддингов",
                        tint = SettingsStrongTextColor
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .background(SettingsFieldBackground, RoundedCornerShape(12.dp))
                    .border(1.dp, SettingsDefaultBorder, RoundedCornerShape(12.dp))
            ) {
                availableModels.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                ),
                                color = SettingsStrongTextColor
                            )
                        },
                        onClick = {
                            onModelSelected(model)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun VoiceRecognitionModelDropdown(
    selectedModel: VoiceRecognitionModel,
    availableModels: List<VoiceRecognitionModel>,
    onModelSelected: (VoiceRecognitionModel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.label_voice_recognition_model),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium
            ),
            color = SettingsLabelColor,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                enabled = availableModels.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(SettingsControlHeight),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SettingsFieldBackground,
                    contentColor = SettingsStrongTextColor
                ),
                border = BorderStroke(1.dp, SettingsDefaultBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedModel.displayName,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
                        color = SettingsStrongTextColor
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select voice recognition model",
                        tint = SettingsStrongTextColor
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .background(SettingsFieldBackground, RoundedCornerShape(12.dp))
                    .border(1.dp, SettingsDefaultBorder, RoundedCornerShape(12.dp))
            ) {
                availableModels.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                ),
                                color = SettingsStrongTextColor
                            )
                        },
                        onClick = {
                            onModelSelected(model)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private val PreviewSettingsState = SettingsState(
    gigaChatKey = "giga-xxxxxxxx",
    qwenChatKey = "qwen-xxxxxxxx",
    aiTunnelKey = "aitunnel-xxxxxxxx",
    anthropicKey = "anthropic-xxxxxxxx",
    openaiKey = "openai-xxxxxxxx",
    saluteSpeechKey = "salute-xxxxxxxx",
    mcpServersJson = """
        {
          "servers": {
            "filesystem": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "."] }
          }
        }
    """.trimIndent(),
    useFewShotExamples = true,
    useStreaming = true,
    safeModeEnabled = true,
    gigaModel = GigaModel.Max,
    embeddingsModel = EmbeddingsModel.GigaEmbeddings,
    voiceRecognitionModel = VoiceRecognitionModel.SaluteSpeech,
    availableLlmModels = GigaModel.entries.take(3),
    availableEmbeddingsModels = EmbeddingsModel.entries.take(2),
    availableVoiceRecognitionModels = VoiceRecognitionModel.entries.take(3),
    systemPrompt = "Ты полезный ассистент. Отвечай кратко и по делу.",
    requestTimeoutMillis = 15000,
    requestTimeoutInput = "15000",
    contextSize = 16000,
    contextSizeInput = "16000",
    temperature = 0.8f,
    temperatureInput = "0.8",
    supportEmail = "support@example.com",
    isBalanceLoading = false,
    balance = listOf(
        GigaResponse.BalanceItem(usage = "REQUESTS", value = 12450),
        GigaResponse.BalanceItem(usage = "TOKENS", value = 382000),
    ),
    defaultCalendar = "Work",
    availableCalendars = listOf("Work", "Personal", "Team"),
    voiceSpeed = 110,
    voiceSpeedInput = "110",
    sendLogsMessage = "Нажмите кнопку, чтобы отправить диагностические логи."
)

@Composable
private fun SettingsSectionPreviewContainer(content: @Composable () -> Unit) {
    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0B0E11)
        ) {
            content()
        }
    }
}

@Preview
@Composable
private fun ModelsSettingsContentPreview() {
    SettingsSectionPreviewContainer {
        ModelsSettingsContent(
            state = PreviewSettingsState,
            onModelChange = {},
            onEmbeddingsModelChange = {},
            onVoiceRecognitionModelChange = {},
            onTemperatureInput = {},
            onRequestTimeoutMillisChange = {},
            onContextSizeInput = {},
            onSystemPromptChange = {},
            onSystemPromptReset = {},
            onRefreshBalance = {},
            onClose = {}
        )
    }
}

@Preview
@Composable
private fun GeneralSettingsContentPreview() {
    SettingsSectionPreviewContainer {
        GeneralSettingsContent(
            state = PreviewSettingsState,
            onDefaultCalendarChange = {},
            onUseStreamingChange = {},
            onNotificationSoundEnabledChange = {},
            onUseEnglishVersionChange = {},
            onChooseVoice = {},
            onVoiceSpeedInput = {},
            onMcpServersJsonInput = {},
            onClose = {}
        )
    }
}

@Preview
@Composable
private fun KeysSettingsContentPreview() {
    SettingsSectionPreviewContainer {
        KeysSettingsContent(
            state = PreviewSettingsState,
            onGigaChatKeyInput = {},
            onQwenChatKeyInput = {},
            onAiTunnelKeyInput = {},
            onAnthropicKeyInput = {},
            onOpenAiKeyInput = {},
            onSaluteSpeechKeyInput = {},
            onOpenProviderLink = {},
            onClose = {}
        )
    }
}

@Preview
@Composable
private fun FunctionsSettingsContentPreview() {
    SettingsSectionPreviewContainer {
        FunctionsSettingsContent(
            state = PreviewSettingsState,
            onUseFewShotExamplesChange = {},
            onAgentSelected = {},
            onConfirmAgentSwitch = {},
            onCancelAgentSwitch = {},
            onOpenTools = {},
            onOpenTelegramSettings = {},
            onClose = {}
        )
    }
}

@Preview
@Composable
private fun SecuritySettingsContentPreview() {
    SettingsSectionPreviewContainer {
        SecuritySettingsContent(
            state = PreviewSettingsState,
            onSafeModeChange = {},
            onOpenFoldersManagement = {},
            onClose = {}
        )
    }
}

@Preview
@Composable
private fun TelegramSettingsScreenPreview() {
    SettingsSectionPreviewContainer {
        TelegramSettingsScreen(
            state = PreviewSettingsState.copy(telegramAuthStep = TelegramAuthStepUi.PHONE),
            onClose = {},
            onStartWork = {},
            onCreateControlBot = {},
            onDisconnectControlBot = {},
            onConfirmDisconnectControlBot = {},
            onCancelDisconnectControlBot = {},
        )
    }
}

@Preview
@Composable
private fun SupportSettingsContentPreview() {
    SettingsSectionPreviewContainer {
        SupportSettingsContent(
            state = PreviewSettingsState,
            onSupportEmailInput = {},
            onSendLogs = {},
            onOpenPrivacyPolicy = {},
            clipboardManager = LocalClipboardManager.current,
            onShowSnack = {},
            onOpenGraphSessions = {},
            onClose = {}
        )
    }
}
