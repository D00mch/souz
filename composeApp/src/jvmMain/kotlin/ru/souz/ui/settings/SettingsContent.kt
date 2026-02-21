package ru.souz.ui.settings

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
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
import ru.souz.giga.EmbeddingsModel
import ru.souz.giga.GigaModel
import ru.souz.giga.GigaResponse
import ru.souz.ui.AppTheme
import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.common.ApiKeyProvider
import ru.souz.ui.common.ApiKeysBuildProfile
import ru.souz.ui.common.configuredApiKeysCount
import ru.souz.ui.components.LabeledTextField
import ru.souz.ui.glassColors
import org.jetbrains.compose.resources.stringResource
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*

private val SettingsFieldBackground = Color(0x66000000)
private val SettingsButtonBackground = Color(0x4D000000)
private val SettingsCheckboxWrapperBackground = Color(0x40000000)
private val SettingsDefaultBorder = Color(0x26FFFFFF)
private val SettingsCheckboxBorder = Color(0x4DFFFFFF)
private val SettingsStrongTextColor = Color(0xF2FFFFFF)
private val SettingsDescriptionColor = Color(0x99FFFFFF)
private val SettingsHintColor = Color(0x80FFFFFF)
private val SettingsLabelColor = Color(0xE6FFFFFF)
private val SettingsAccent = Color(0xFF12E0B5)
private val SettingsAccentBackground = Color(0x1A12E0B5)
private val SettingsAccentActiveBackground = Color(0x3312E0B5)
private val SettingsContentGradientTop = Color(0xFF0A0A0A)
private val SettingsContentGradientMiddle = Color(0xFF050505)
private val SettingsContentGradientBottom = Color(0xFF0A0A0A)
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

private object SettingsSpacing {
    val screenPaddingHorizontal = 32.dp
    val screenPaddingTop = 24.dp
    val screenPaddingBottom = 24.dp
    val sectionSpacing = 32.dp
    val elementSpacing = 16.dp
    val labelToFieldSpacing = 8.dp
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
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SettingsCheckboxWrapperBackground)
            .padding(6.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(SettingsFieldBackground),
            colors = CheckboxDefaults.colors(
                checkedColor = SettingsAccent,
                uncheckedColor = SettingsCheckboxBorder,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary
            )
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
    val windowInfo = LocalWindowInfo.current
    val sectionBackgroundAlpha by animateFloatAsState(
        targetValue = if (windowInfo.isWindowFocused) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "settingsSectionBackgroundAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SettingsContentGradientTop.copy(alpha = sectionBackgroundAlpha),
                        SettingsContentGradientMiddle.copy(alpha = sectionBackgroundAlpha),
                        SettingsContentGradientBottom.copy(alpha = sectionBackgroundAlpha)
                    )
                )
            )
    ) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 36.sp
                        ),
                        color = SettingsStrongTextColor
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 20.sp
                        ),
                        color = SettingsDescriptionColor
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Закрыть",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            content()
        }
    }
}

@Composable
fun ModelsSettingsContent(
    state: SettingsState,
    onModelChange: (GigaModel) -> Unit,
    onEmbeddingsModelChange: (EmbeddingsModel) -> Unit,
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

            Row(horizontalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)) {
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
    onVoiceSpeedInput: (String) -> Unit,
    onChooseVoice: () -> Unit,
    onMcpServersJsonInput: (String) -> Unit,
    onClose: () -> Unit
) {
    SettingsSectionScreen(
        title = stringResource(Res.string.settings_section_general),
        subtitle = stringResource(Res.string.settings_general_subtitle),
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)) {
            CalendarDropdown(
                selectedCalendar = state.defaultCalendar,
                availableCalendars = state.availableCalendars,
                isLoading = state.isLoadingCalendars,
                onCalendarSelected = onDefaultCalendarChange
            )

            SettingsGroupDivider()

            SettingsRow(
                title = stringResource(Res.string.setting_streaming_title),
                description = stringResource(Res.string.setting_streaming_desc),
                content = {
                    SettingsCheckbox(
                        checked = state.useStreaming,
                        onCheckedChange = onUseStreamingChange
                    )
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                LabeledTextField(
                    label = stringResource(Res.string.label_voice_speed),
                    value = state.voiceSpeedInput,
                    onValueChange = onVoiceSpeedInput,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(Res.string.hint_voice_speed).format(state.voiceSpeed),
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsHintColor
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                Text(
                    text = stringResource(Res.string.label_voice_selection),
                     style = MaterialTheme.typography.labelMedium.copy(
                         fontSize = 14.sp,
                         lineHeight = 20.sp,
                         fontWeight = FontWeight.Medium
                     ),
                     color = SettingsStrongTextColor
                )
                OutlinedButton(
                    onClick = onChooseVoice,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SettingsButtonBackground,
                        contentColor = SettingsStrongTextColor
                    ),
                    border = BorderStroke(1.dp, SettingsDefaultBorder),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.button_select_voice),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = SettingsStrongTextColor
                    )
                }
            }
            
            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                Text(
                    text = stringResource(Res.string.label_mcp_servers),
                     style = MaterialTheme.typography.labelMedium.copy(
                         fontSize = 14.sp,
                         lineHeight = 20.sp,
                         fontWeight = FontWeight.Medium
                     ),
                     color = SettingsStrongTextColor
                )
                LabeledTextField(
                    label = "",
                    value = state.mcpServersJson,
                    onValueChange = onMcpServersJsonInput,
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    singleLine = false,
                )
                Text(
                    text = stringResource(Res.string.hint_restart_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsHintColor
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
    val configuredKeysCount = configuredApiKeysCount(
        gigaChatKey = state.gigaChatKey,
        qwenChatKey = state.qwenChatKey,
        aiTunnelKey = state.aiTunnelKey,
        anthropicKey = state.anthropicKey,
        openaiKey = state.openaiKey,
        saluteSpeechKey = state.saluteSpeechKey,
    )
    val supportsSaluteSpeech = ApiKeysBuildProfile.hasField(ApiKeyField.SALUTE_SPEECH)
    val supportsVoiceRecognition = ApiKeysBuildProfile.supportsSpeechRecognition
    val keysHintChat = if (ApiKeysBuildProfile.hasField(ApiKeyField.GIGA_CHAT)) {
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
                        text = stringResource(Res.string.keys_configured_count).format(configuredKeysCount),
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


            if (ApiKeysBuildProfile.hasField(ApiKeyField.GIGA_CHAT)) {
                LabeledTextField(
                    label = stringResource(Res.string.label_key_gigachat),
                    value = state.gigaChatKey,
                    onValueChange = onGigaChatKeyInput,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (ApiKeysBuildProfile.hasField(ApiKeyField.QWEN_CHAT)) {
                LabeledTextField(
                    label = stringResource(Res.string.label_key_qwen),
                    value = state.qwenChatKey,
                    onValueChange = onQwenChatKeyInput,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (ApiKeysBuildProfile.hasField(ApiKeyField.AI_TUNNEL)) {
                LabeledTextField(
                    label = stringResource(Res.string.label_key_aitunnel),
                    value = state.aiTunnelKey,
                    onValueChange = onAiTunnelKeyInput,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (ApiKeysBuildProfile.hasField(ApiKeyField.ANTHROPIC)) {
                LabeledTextField(
                    label = stringResource(Res.string.label_key_anthropic),
                    value = state.anthropicKey,
                    onValueChange = onAnthropicKeyInput,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (ApiKeysBuildProfile.hasField(ApiKeyField.OPENAI)) {
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
            ApiKeysBuildProfile.providers.forEach { provider ->
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
                    .height(48.dp),
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
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SettingsButtonBackground,
                    contentColor = SettingsStrongTextColor
                ),
                border = BorderStroke(1.dp, SettingsDefaultBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
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
                            SettingsAccent
                        } else {
                            SettingsHintColor
                        }
                    )
                }
            }
        }
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
                Text(
                    text = stringResource(Res.string.hint_forbidden_folders),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    color = SettingsHintColor
                )
                Button(
                    onClick = onOpenFoldersManagement,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
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

@Composable
fun TelegramSettingsScreen(
    state: SettingsState,
    onClose: () -> Unit,
    onStartWork: () -> Unit,
    onCreateControlBot: () -> Unit,
    onDisconnectControlBot: () -> Unit,
) {
    SettingsSectionScreen(
        title = "Telegram",
        subtitle = "",
        onClose = onClose
    ) {
        TelegramLoginContent(
            state = state,
            onStartWork = onStartWork,
            onCreateControlBot = onCreateControlBot,
            onDisconnectControlBot = onDisconnectControlBot,
        )
    }
}

@Composable
fun SupportSettingsContent(
    state: SettingsState,
    onSupportEmailInput: (String) -> Unit,
    onSendLogs: () -> Unit,
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
            Button(
                onClick = onOpenGraphSessions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)
    ) {
        content()
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
             Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = SettingsHintColor
            )
        }
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
            .height(48.dp),
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
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium
            ),
            color = SettingsLabelColor,
        )

        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().height(56.dp),
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
                            fontSize = 15.sp,
                            lineHeight = 22.sp
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
                                fontSize = 15.sp,
                                lineHeight = 22.sp
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
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
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
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
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
        Text(
            text = stringResource(Res.string.hint_calendar_usage),
            style = MaterialTheme.typography.bodySmall,
            color = SettingsHintColor
        )
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
                color = SettingsAccent.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = 1.dp,
                color = SettingsAccent.copy(alpha = 0.25f),
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
                    tint = MaterialTheme.colorScheme.primary
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
                    .background(SettingsAccentActiveBackground)
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
                        color = SettingsAccent,
                        strokeWidth = 1.8.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = SettingsAccent,
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
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium
            ),
            color = SettingsLabelColor,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                enabled = availableModels.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
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
                            fontSize = 15.sp,
                            lineHeight = 22.sp
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
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
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
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium
            ),
            color = SettingsLabelColor,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                enabled = availableModels.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
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
                            fontSize = 15.sp,
                            lineHeight = 22.sp
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
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
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
            onVoiceSpeedInput = {},
            onChooseVoice = {},
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
            clipboardManager = LocalClipboardManager.current,
            onShowSnack = {},
            onOpenGraphSessions = {},
            onClose = {}
        )
    }
}
