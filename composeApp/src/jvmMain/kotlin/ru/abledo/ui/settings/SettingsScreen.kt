package ru.abledo.ui.settings

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI
import ru.abledo.agent.DEFAULT_SYSTEM_PROMPT
import ru.abledo.giga.GigaResponse
import ru.abledo.ui.AppTheme
import ru.abledo.ui.glassColors
import ru.abledo.ui.main.GlassCard

private val SettingsWindowSize = DpSize(width = 560.dp, height = 650.dp)

@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onOpenTools: () -> Unit,
    onResizeRequest: (DpSize) -> Unit = {},
    onShowSnack: (String) -> Unit = {},
) {
    val di = localDI()
    val viewModel = viewModel { SettingsViewModel(di) }
    val state = viewModel.uiState.collectAsState().value

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.CloseScreen -> Unit
                SettingsEffect.NotifyOnSystemPrompt -> onShowSnack("Сохранено. Применится после первой суммаризации")
            }
        }
    }

    SettingsScreen(
        state,
        onGigaChatKeyInput = { key -> viewModel.send(SettingsEvent.InputGigaChatKey(key)) },
        onSaluteSpeechKeyInput = { key -> viewModel.send(SettingsEvent.InputSaluteSpeechKey(key)) },
        onUseFewShotExamplesChange = { enabled -> viewModel.send(SettingsEvent.InputUseFewShotExamples(enabled)) },
        onDefaultCalendarChange = { calName -> viewModel.send(SettingsEvent.SelectDefaultCalendar(calName)) },
        onSupportEmailInput = { email -> viewModel.send(SettingsEvent.InputSupportEmail(email)) },
        onSystemPromptChange = { prompt -> viewModel.send(SettingsEvent.InputSystemPrompt(prompt)) },
        onSystemPromptReset = { viewModel.send(SettingsEvent.ResetSystemPrompt) },
        onSendLogs = { viewModel.send(SettingsEvent.SendLogsToSupport) },
        onRefreshBalance = { viewModel.send(SettingsEvent.RefreshBalance) },
        onOpenTools = onOpenTools,
        onResizeRequest = onResizeRequest,
        onClose = onClose,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsState,
    onGigaChatKeyInput: (String) -> Unit,
    onSaluteSpeechKeyInput: (String) -> Unit,
    onUseFewShotExamplesChange: (Boolean) -> Unit,
    onDefaultCalendarChange: (String?) -> Unit,
    onSupportEmailInput: (String) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onSystemPromptReset: () -> Unit,
    onSendLogs: () -> Unit,
    onRefreshBalance: () -> Unit,
    onOpenTools: () -> Unit,
    onResizeRequest: (DpSize) -> Unit = {},
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) { onResizeRequest(SettingsWindowSize) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(modifier = Modifier.fillMaxSize()) {
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

                Button(onClick = onOpenTools, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Инструменты",
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

                CalendarDropdown(
                    selectedCalendar = state.defaultCalendar,
                    availableCalendars = state.availableCalendars,
                    isLoading = state.isLoadingCalendars,
                    onCalendarSelected = onDefaultCalendarChange
                )

                LabeledTextField(
                    label = "Email поддержки",
                    value = state.supportEmail,
                    onValueChange = onSupportEmailInput,
                    modifier = Modifier.fillMaxWidth()
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

                // --- ЛОГИ ---
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                            textAlign = TextAlign.Start
                        )
                    }
                }
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
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.glassColors.textPrimary
        )

        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    backgroundColor = Color.Transparent,
                    contentColor = MaterialTheme.glassColors.textPrimary
                ),
                border = BorderStroke(1.dp, MaterialTheme.glassColors.textPrimary.copy(alpha = 0.3f))
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
                // Опция "Сбросить"
                DropdownMenuItem(onClick = {
                    onCalendarSelected(null)
                    expanded = false
                }) {
                    Text("Не выбран (системный)")
                }

                if (availableCalendars.isEmpty() && !isLoading) {
                    DropdownMenuItem(enabled = false, onClick = {}) {
                        Text("Нет доступных календарей")
                    }
                }

                availableCalendars.forEach { calendarName ->
                    DropdownMenuItem(onClick = {
                        onCalendarSelected(calendarName)
                        expanded = false
                    }) {
                        Text(calendarName)
                    }
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
private fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.glassColors.textPrimary
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = MaterialTheme.glassColors.textPrimary,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                unfocusedBorderColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.3f),
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f),
            ),
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
            onUseFewShotExamplesChange = {},
            onDefaultCalendarChange = {},
            onSupportEmailInput = {},
            onSystemPromptChange = {},
            onSystemPromptReset = {},
            onSendLogs = {},
            onRefreshBalance = {},
            onOpenTools = {},
            onResizeRequest = {},
            onClose = {},
        )
    }
}