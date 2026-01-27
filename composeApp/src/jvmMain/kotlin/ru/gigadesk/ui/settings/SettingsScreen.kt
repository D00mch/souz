package ru.gigadesk.ui.settings

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.debounce
import org.kodein.di.compose.localDI
import org.kodein.di.instance
import ru.gigadesk.agent.DEFAULT_SYSTEM_PROMPT
import ru.gigadesk.agent.session.GraphSessionRepository
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.ui.AppTheme
import ru.gigadesk.ui.components.LabeledTextField
import ru.gigadesk.ui.glassColors
import ru.gigadesk.ui.graphlog.GraphSessionsScreen
import ru.gigadesk.ui.graphlog.GraphVisualizationScreen
import ru.gigadesk.ui.main.RealLiquidGlassCard

@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onOpenTools: () -> Unit,
    onShowSnack: (String) -> Unit = {},
) {
    val di = localDI()
    val viewModel = viewModel { SettingsViewModel(di) }
    val state = viewModel.uiState.collectAsState().value
    val sessionRepository: GraphSessionRepository by di.instance()

    LaunchedEffect(viewModel) {
        @Suppress("OPT_IN_USAGE")
        viewModel.effects.debounce(2000).collect { effect ->
            when (effect) {
                SettingsEffect.CloseScreen -> Unit
                SettingsEffect.NotifyOnSystemPrompt -> onShowSnack("Сохранено. Применится после первой суммаризации")
            }
        }
    }
    
    when (state.currentScreen) {
        SettingsSubScreen.MAIN -> {
            SettingsScreen(
                state,
                onGigaChatKeyInput = { key -> viewModel.send(SettingsEvent.InputGigaChatKey(key)) },
                onSaluteSpeechKeyInput = { key -> viewModel.send(SettingsEvent.InputSaluteSpeechKey(key)) },
                onVoiceSpeedInput = { speed -> viewModel.send(SettingsEvent.InputVoiceSpeed(speed)) },
                onChooseVoice = { viewModel.send(SettingsEvent.ChooseVoice) },
                onUseFewShotExamplesChange = { enabled -> viewModel.send(SettingsEvent.InputUseFewShotExamples(enabled)) },
                onUseGrpcDelegateChange = { enabled -> viewModel.send(SettingsEvent.InputUseGrpcDelegate(enabled)) },
                onModelChange = { model -> viewModel.send(SettingsEvent.SelectModel(model)) },
                onRequestTimeoutMillisChange = { value -> viewModel.send(SettingsEvent.InputRequestTimeoutMillis(value)) },
                onTemperatureInput = { value -> viewModel.send(SettingsEvent.InputTemperature(value)) },
                onDefaultCalendarChange = { calName -> viewModel.send(SettingsEvent.SelectDefaultCalendar(calName)) },
                onSupportEmailInput = { email -> viewModel.send(SettingsEvent.InputSupportEmail(email)) },
                onSystemPromptChange = { prompt -> viewModel.send(SettingsEvent.InputSystemPrompt(prompt)) },
                onSystemPromptReset = { viewModel.send(SettingsEvent.ResetSystemPrompt) },
                onSendLogs = { viewModel.send(SettingsEvent.SendLogsToSupport) },
                onRefreshBalance = { viewModel.send(SettingsEvent.RefreshBalance) },
                onOpenTools = onOpenTools,
                onClose = onClose,
                onShowSnack = onShowSnack,
                onOpenGraphSessions = { viewModel.send(SettingsEvent.OpenGraphSessions) }
            )
        }
        SettingsSubScreen.SESSIONS -> {
            GraphSessionsScreen(
                sessionRepository = sessionRepository,
                onClose = { viewModel.send(SettingsEvent.BackToSettings) },
                onSelectSession = { session -> viewModel.send(SettingsEvent.OpenGraphVisualization(session.id)) }
            )
        }
        SettingsSubScreen.VISUALIZATION -> {
            val session = remember(state.selectedSessionId) {
                state.selectedSessionId?.let { sessionRepository.loadById(it) }
            }
            if (session != null) {
                GraphVisualizationScreen(
                    session = session,
                    onBack = { viewModel.send(SettingsEvent.BackToSessions) }
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Сессия не найдена", color = Color.White)
                    Button(onClick = { viewModel.send(SettingsEvent.BackToSessions) }) { Text("Назад") }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: SettingsState,
    onGigaChatKeyInput: (String) -> Unit,
    onSaluteSpeechKeyInput: (String) -> Unit,
    onVoiceSpeedInput: (String) -> Unit,
    onChooseVoice: () -> Unit,
    onUseFewShotExamplesChange: (Boolean) -> Unit,
    onUseGrpcDelegateChange: (Boolean) -> Unit,
    onModelChange: (GigaModel) -> Unit,
    onRequestTimeoutMillisChange: (String) -> Unit,
    onTemperatureInput: (String) -> Unit,
    onDefaultCalendarChange: (String?) -> Unit,
    onSupportEmailInput: (String) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onSystemPromptReset: () -> Unit,
    onSendLogs: () -> Unit,
    onRefreshBalance: () -> Unit,
    onOpenTools: () -> Unit,
    onClose: () -> Unit,
    onShowSnack: (String) -> Unit = {},
    onOpenGraphSessions: () -> Unit = {},
) {
    // Получаем состояние фокуса окна
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused
    val clipboardManager = LocalClipboardManager.current

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Передаем isWindowFocused в RealLiquidGlassCard
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = isFocused
        ) {
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
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

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabeledTextField(
                        label = "Скорость речи",
                        value = state.voiceSpeedInput,
                        onValueChange = onVoiceSpeedInput,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Текущее значение: ${state.voiceSpeed}. Чем больше значение, тем быстрее речь.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
                    )
                }


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


                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabeledTextField(
                        label = "Таймаут запроса (мс)",
                        value = state.requestTimeoutInput,
                        onValueChange = onRequestTimeoutMillisChange,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Текущее значение: ${state.requestTimeoutMillis} мс.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabeledTextField(
                        label = "Температура",
                        value = state.temperatureInput,
                        onValueChange = onTemperatureInput,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Текущее значение: ${state.temperature}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
                    )
                }

                Button(onClick = onOpenTools, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Настройка инструментов",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.glassColors.textPrimary,
                    )
                }

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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Checkbox(
                        checked = state.useGrpcDelegate,
                        onCheckedChange = onUseGrpcDelegateChange,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f),
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    Text(
                        text = "Использовать gRPC для запросов к модели",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.glassColors.textPrimary
                    )
                }

                CalendarDropdown(
                    selectedCalendar = state.defaultCalendar,
                    availableCalendars = state.availableCalendars,
                    isLoading = state.isLoadingCalendars,
                    onCalendarSelected = onDefaultCalendarChange
                )

                ModelDropdown(
                    selectedModel = state.gigaModel,
                    onModelSelected = onModelChange,
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LabeledTextField(
                        label = "Системный промпт",
                        value = state.systemPrompt,
                        onValueChange = onSystemPromptChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onSystemPromptReset,
                            enabled = state.systemPrompt != DEFAULT_SYSTEM_PROMPT
                        ) {
                            Text(
                                text = "Сбросить по умолчанию",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.glassColors.textPrimary,
                            )
                        }
                    }
                }

                TokensBalanceSection(
                    isLoading = state.isBalanceLoading,
                    balance = state.balance,
                    error = state.balanceError,
                    onRefreshBalance = onRefreshBalance,
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
    }
}

@Composable
private fun LogsView(
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
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.glassColors.textPrimary
        )

        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.glassColors.textPrimary
                ),
                border = BorderStroke(1.dp, MaterialTheme.glassColors.textPrimary.copy(alpha = 0.3f)),
                shape = MaterialTheme.shapes.extraSmall
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
private fun TokensBalanceSection(
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
private fun ModelDropdown(
    selectedModel: GigaModel,
    onModelSelected: (GigaModel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Модель",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.glassColors.textPrimary
        )
        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.glassColors.textPrimary
                ),
                border = BorderStroke(1.dp, MaterialTheme.glassColors.textPrimary.copy(alpha = 0.3f)),
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedModel.name} (${selectedModel.alias})",
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
                        text = { Text("${model.name} (${model.alias})") },
                        onClick = {
                            onModelSelected(model)
                            expanded = false
                        }
                    )
                }
            }
        }
        Text(
            text = "Выберите модель, которая будет использована для запросов.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
        )
    }
}

@Preview
@Composable
fun SettingsScreenPreview() {
    AppTheme {
        SettingsScreen(
            state = SettingsState(gigaChatKey = "key1", saluteSpeechKey = "key2", useFewShotExamples = true),
            onGigaChatKeyInput = {},
            onSaluteSpeechKeyInput = {},
            onVoiceSpeedInput = {},
            onChooseVoice = {},
            onUseFewShotExamplesChange = {},
            onUseGrpcDelegateChange = {},
            onModelChange = {},
            onRequestTimeoutMillisChange = {},
            onTemperatureInput = {},
            onDefaultCalendarChange = {},
            onSupportEmailInput = {},
            onSystemPromptChange = {},
            onSystemPromptReset = {},
            onSendLogs = {},
            onRefreshBalance = {},
            onOpenTools = {},
            onClose = {},
            onShowSnack = {},
        )
    }
}
