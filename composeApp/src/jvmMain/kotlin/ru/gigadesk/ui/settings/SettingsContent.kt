package ru.gigadesk.ui.settings

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
import ru.gigadesk.giga.EmbeddingsModel
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.ui.AppTheme
import ru.gigadesk.ui.common.ApiKeyProvider
import ru.gigadesk.ui.common.configuredApiKeysCount
import ru.gigadesk.ui.components.LabeledTextField
import ru.gigadesk.ui.glassColors

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
        title = "Модели",
        subtitle = "Настройки моделей и параметров генерации",
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)) {
            ModelDropdown(
                selectedModel = state.gigaModel,
                onModelSelected = onModelChange,
            )

            EmbeddingsModelDropdown(
                selectedModel = state.embeddingsModel,
                onModelSelected = onEmbeddingsModelChange,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)
                ) {
                    LabeledTextField(
                        label = "Температура",
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
                        label = "Таймаут (мс)",
                        value = state.requestTimeoutInput,
                        onValueChange = onRequestTimeoutMillisChange,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                LabeledTextField(
                    label = "Размер контекстного окна",
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
                    text = "Системный промпт",
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
                        text = "Сбросить",
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
        title = "Общие",
        subtitle = "Основные настройки приложения",
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
                title = "Streaming-режим",
                description = "Ответы появляются постепенно, по мере генерации",
                content = {
                    SettingsCheckbox(
                        checked = state.useStreaming,
                        onCheckedChange = onUseStreamingChange
                    )
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                LabeledTextField(
                    label = "Скорость речи",
                    value = state.voiceSpeedInput,
                    onValueChange = onVoiceSpeedInput,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Текущее значение: ${state.voiceSpeed}. Чем больше, тем быстрее речь",
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsHintColor
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                Text(
                    text = "Выбор голоса",
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
                        text = "Выбрать голос",
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
                    text = "MCP servers JSON",
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
                    text = "Изменения применятся после перезапуска приложения",
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
    onSaluteSpeechKeyInput: (String) -> Unit,
    onOpenProviderLink: (ApiKeyProvider) -> Unit,
    onClose: () -> Unit
) {
    val configuredKeysCount = configuredApiKeysCount(
        gigaChatKey = state.gigaChatKey,
        qwenChatKey = state.qwenChatKey,
        aiTunnelKey = state.aiTunnelKey,
        saluteSpeechKey = state.saluteSpeechKey,
    )
    SettingsSectionScreen(
        title = "Мои ключи",
        subtitle = "Настройка ключей и быстрые ссылки на сервисы",
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
                        text = "Настроено ключей: $configuredKeysCount / 4",
                        style = MaterialTheme.typography.titleSmall,
                        color = SettingsStrongTextColor
                    )
                    Text(
                        text = "Для чата достаточно одного ключа: GigaChat, Qwen или AI Tunnel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SettingsHintColor
                    )
                    Text(
                        text = "Для голосовых команд нужен SaluteSpeech ключ из кабинета Sber.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SettingsHintColor
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
                label = "Qwen ключ",
                value = state.qwenChatKey,
                onValueChange = onQwenChatKeyInput,
                modifier = Modifier.fillMaxWidth()
            )
            LabeledTextField(
                label = "AI Tunnel ключ",
                value = state.aiTunnelKey,
                onValueChange = onAiTunnelKeyInput,
                modifier = Modifier.fillMaxWidth()
            )
        }

        SettingsGroupDivider()

        LabeledTextField(
            label = "SaluteSpeech ключ",
            value = state.saluteSpeechKey,
            onValueChange = onSaluteSpeechKeyInput,
            modifier = Modifier.fillMaxWidth()
        )

        SettingsGroupDivider()

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Где получить ключи",
                style = MaterialTheme.typography.titleMedium,
                color = SettingsStrongTextColor
            )
            ApiKeyProvider.entries.forEach { provider ->
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
                text = provider.title,
                style = MaterialTheme.typography.titleSmall,
                color = SettingsStrongTextColor
            )
            Text(
                text = provider.description,
                style = MaterialTheme.typography.bodySmall,
                color = SettingsHintColor
            )
            Text(
                text = provider.details,
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

    onClose: () -> Unit
) {
    SettingsSectionScreen(
        title = "Функции",
        subtitle = "Настройки инструментов и возможностей агента",
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)) {
            SettingsRow(
                title = "Few-Shot Examples",
                description = "Класть примеры использования тулов в контекст",
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
                    text = "Настройка инструментов",
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
}

@Composable
fun SecuritySettingsContent(
    state: SettingsState,
    onSafeModeChange: (Boolean) -> Unit,
    onOpenFoldersManagement: () -> Unit,
    onClose: () -> Unit
) {
    SettingsSectionScreen(
        title = "Безопасность",
        subtitle = "Настройки безопасности и ограничений",
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.elementSpacing)) {
            SettingsRow(
                title = "Безопасный режим",
                description = "Запрашивать подтверждение опасных действий (удаление файлов, отправка данных)",
                content = {
                    SettingsCheckbox(
                        checked = state.safeModeEnabled,
                        onCheckedChange = onSafeModeChange
                    )
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.labelToFieldSpacing)) {
                Text(
                    text = "Запретные папки",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = SettingsStrongTextColor
                )
                Text(
                    text = "Открыть отдельный экран управления доступом к папкам",
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
                        text = "Управление запретными папками",
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
        title = "Поддержка",
        subtitle = "Связь с разработчиками и отладка",
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
                    text = "История сессий графа",
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
            label = "Email поддержки",
            value = state.supportEmail,
            onValueChange = onSupportEmailInput,
            modifier = Modifier.fillMaxWidth()
        )
        SendLogsButton(
            isSending = state.isSendingLogs,
            onSendLogs = onSendLogs
        )
        state.sendLogsMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.glassColors.textPrimary,
                textAlign = TextAlign.Start,
                modifier = Modifier.clickable(enabled = state.sendLogsPath != null) {
                    state.sendLogsPath?.let { path ->
                        clipboardManager.setText(AnnotatedString(path))
                        onShowSnack("Путь к логам скопирован")
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
                        text = "Отправка логов...",
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
                    text = "Отправить логи",
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
            text = "Календарь по умолчанию",
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
                            isLoading -> "Загрузка календарей..."
                            selectedCalendar.isNullOrBlank() -> "Не выбран (системный по умолчанию)"
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
                        contentDescription = "Выбрать календарь",
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
                            text = "Не выбран (системный)",
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
                                text = "Нет доступных календарей",
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
            text = "Агент будет использовать этот календарь для создания событий, если вы не укажете иное.",
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
                    text = "Остаток токенов",
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
                text = "Запрашиваем баланс...",
                style = MaterialTheme.typography.bodyMedium,
                color = SettingsStrongTextColor,
            )

            error != null -> Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            balance.isEmpty() -> Text(
                text = "Нет данных о балансе",
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
    onModelSelected: (GigaModel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Модель",
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
                GigaModel.entries.forEach { model ->
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
    onModelSelected: (EmbeddingsModel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Модель эмбеддингов",
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
                EmbeddingsModel.entries.forEach { model ->
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
