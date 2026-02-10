package ru.gigadesk.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.gigadesk.giga.EmbeddingsModel
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.ui.components.LabeledTextField
import ru.gigadesk.ui.glassColors

@Composable
fun SettingsContentHeader(
    title: String,
    description: String,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.glassColors.textPrimary
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.7f)
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Закрыть настройки",
                tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.5f)
            )
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
    onSystemPromptChange: (String) -> Unit,
    onSystemPromptReset: () -> Unit,
    onRefreshBalance: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SettingsContentHeader(
            title = "Модели",
            description = "Настройки моделей и параметров генерации",
            onClose = onClose
        )

        ModelDropdown(
            selectedModel = state.gigaModel,
            onModelSelected = onModelChange,
        )

        EmbeddingsModelDropdown(
            selectedModel = state.embeddingsModel,
            onModelSelected = onEmbeddingsModelChange,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LabeledTextField(
                    label = "Температура",
                    value = state.temperatureInput,
                    onValueChange = onTemperatureInput,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Текущее: ${state.temperature}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LabeledTextField(
                    label = "Таймаут (мс)",
                    value = state.requestTimeoutInput,
                    onValueChange = onRequestTimeoutMillisChange,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${state.requestTimeoutMillis} мс",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                 Text(
                    text = "Системный промпт",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.glassColors.textPrimary
                )
                TextButton(onClick = onSystemPromptReset) {
                    Text("Сбросить", color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f))
                }
            }
           
            LabeledTextField(
                label = "",
                value = state.systemPrompt,
                onValueChange = onSystemPromptChange,
                modifier = Modifier.fillMaxWidth().height(150.dp),
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SettingsContentHeader(
            title = "Общие",
            description = "Основные настройки приложения",
            onClose = onClose
        )

        CalendarDropdown(
            selectedCalendar = state.defaultCalendar,
            availableCalendars = state.availableCalendars,
            isLoading = state.isLoadingCalendars,
            onCalendarSelected = onDefaultCalendarChange
        )

        SettingsRow(
            title = "Streaming-режим",
            description = "Ответы появляются постепенно, по мере генерации",
            content = {
                 Checkbox(
                    checked = state.useStreaming,
                    onCheckedChange = onUseStreamingChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f),
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabeledTextField(
                label = "Скорость речи",
                value = state.voiceSpeedInput,
                onValueChange = onVoiceSpeedInput,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Текущее значение: ${state.voiceSpeed}. Чем больше, тем быстрее речь",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Выбор голоса",
                 style = MaterialTheme.typography.titleMedium,
                 color = MaterialTheme.glassColors.textPrimary
            )
            OutlinedButton(
                onClick = onChooseVoice,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(
                    text = "Выбрать голос",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.glassColors.textPrimary
                )
            }
        }
        
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "MCP servers JSON",
                 style = MaterialTheme.typography.titleMedium,
                 color = MaterialTheme.glassColors.textPrimary
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
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
            )
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
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SettingsContentHeader(
            title = "Мои ключи",
            description = "API ключи для доступа к сервисам",
            onClose = onClose
        )

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
        LabeledTextField(
            label = "SaluteSpeech ключ",
            value = state.saluteSpeechKey,
            onValueChange = onSaluteSpeechKeyInput,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun FunctionsSettingsContent(
    state: SettingsState,
    onUseFewShotExamplesChange: (Boolean) -> Unit,
    onOpenTools: () -> Unit,

    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SettingsContentHeader(
            title = "Функции",
            description = "Настройки инструментов и возможностей агента",
            onClose = onClose
        )

        SettingsRow(
            title = "Few-Shot Examples",
            description = "Класть примеры использования тулов в контекст",
            content = {
                 Checkbox(
                    checked = state.useFewShotExamples,
                    onCheckedChange = onUseFewShotExamplesChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f),
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        )

        Button(onClick = onOpenTools, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Настройка инструментов",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.glassColors.textPrimary,
            )
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SettingsContentHeader(
            title = "Безопасность",
            description = "Настройки безопасности и ограничений",
            onClose = onClose
        )

        SettingsRow(
            title = "Безопасный режим",
            description = "Запрашивать подтверждение опасных действий (удаление файлов, отправка данных)",
            content = {
                 Checkbox(
                    checked = state.safeModeEnabled,
                    onCheckedChange = onSafeModeChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f),
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        )

        SettingsRow(
            title = "Запретные папки",
            description = "Папки, к которым у агента нет доступа",
            content = {
                OutlinedButton(
                    onClick = onOpenFoldersManagement,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.glassColors.textPrimary
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.glassColors.textPrimary.copy(alpha = 0.3f))
                ) {
                    Text("Настроить")
                }
            }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SettingsContentHeader(
            title = "Поддержка",
            description = "Связь с разработчиками и отладка",
            onClose = onClose
        )

        Button(onClick = onOpenGraphSessions, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "История сессий графа",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.glassColors.textPrimary,
            )
        }

        LogsView(state, onSupportEmailInput, onSendLogs, clipboardManager, onShowSnack)
    }
}

@Composable
fun SettingsRow(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        content()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.glassColors.textPrimary
            )
             Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
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
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LabeledTextField(
            label = "Email поддержки",
            value = state.supportEmail,
            onValueChange = onSupportEmailInput,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onSendLogs,
            enabled = !state.isSendingLogs,
        ) {
            Text(
                text = if (state.isSendingLogs) "Отправка логов..." else "Отправить логи",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.glassColors.textPrimary
            )
        }
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
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.glassColors.textPrimary,
            fontWeight = FontWeight.Medium
        )

        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.05f),
                    contentColor = MaterialTheme.glassColors.textPrimary
                ),
                border = BorderStroke(1.dp, MaterialTheme.glassColors.textPrimary.copy(alpha = 0.1f)),
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.glassColors.textPrimary
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Выбрать календарь",
                        tint = MaterialTheme.glassColors.textPrimary
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                DropdownMenuItem(
                    text = { Text("Не выбран (системный)") },
                    onClick = {
                        onCalendarSelected(null)
                        expanded = false
                    }
                )

                if (availableCalendars.isEmpty() && !isLoading) {
                    DropdownMenuItem(
                        text = { Text("Нет доступных календарей") },
                        enabled = false,
                        onClick = {}
                    )
                }

                availableCalendars.forEach { calendarName ->
                    DropdownMenuItem(
                        text = { Text(calendarName) },
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
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Остаток токенов",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.glassColors.textPrimary,
            )

            Button(onClick = onRefreshBalance, enabled = !isLoading) {
                Text(
                    text = if (isLoading) "Обновление..." else "Обновить",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.glassColors.textPrimary,
                )
            }
        }

        when {
            isLoading -> Text(
                text = "Запрашиваем баланс...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.glassColors.textPrimary,
            )

            error != null -> Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            balance.isEmpty() -> Text(
                text = "Нет данных о балансе",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f),
            )

            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                balance.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.usage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.glassColors.textPrimary,
                        )
                        Text(
                            text = item.value.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.glassColors.textPrimary,
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
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.glassColors.textPrimary,
            fontWeight = FontWeight.Medium
        )
        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.05f),
                    contentColor = MaterialTheme.glassColors.textPrimary
                ),
                border = BorderStroke(1.dp, MaterialTheme.glassColors.textPrimary.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedModel.displayName} (${selectedModel.alias})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.glassColors.textPrimary
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Выбрать модель",
                        tint = MaterialTheme.glassColors.textPrimary
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                GigaModel.entries.forEach { model ->
                    DropdownMenuItem(
                        text = { Text("${model.displayName} (${model.alias})") },
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
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.glassColors.textPrimary,
            fontWeight = FontWeight.Medium
        )
        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.05f),
                    contentColor = MaterialTheme.glassColors.textPrimary
                ),
                border = BorderStroke(1.dp, MaterialTheme.glassColors.textPrimary.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedModel.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.glassColors.textPrimary
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Выбрать модель эмбеддингов",
                        tint = MaterialTheme.glassColors.textPrimary
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                EmbeddingsModel.entries.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.displayName) },
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
