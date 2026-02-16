package ru.gigadesk.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.kodein.di.compose.localDI
import org.kodein.di.instance
import ru.gigadesk.service.telegram.TelegramService
import ru.gigadesk.ui.components.LabeledTextField

@Composable
fun TelegramLoginContent(
    state: SettingsState,
) {
    val di = localDI()
    val telegramService: TelegramService by di.instance()
    val scope = rememberCoroutineScope()

    var phoneValue by remember { mutableStateOf(state.telegramPhoneInput) }
    var codeValue by remember { mutableStateOf(state.telegramCodeInput) }
    var passwordValue by remember { mutableStateOf(state.telegramPasswordInput) }
    var localError by remember { mutableStateOf<String?>(null) }
    var localInfo by remember { mutableStateOf<String?>(null) }

    var advancedExpanded by remember { mutableStateOf(false) }
    var debugLogsEnabled by remember { mutableStateOf(false) }

    suspend fun reloadClientConfig() {
        val config = telegramService.getClientConfig()
        debugLogsEnabled = config.debugLogsEnabled
    }

    LaunchedEffect(Unit) {
        runCatching { reloadClientConfig() }
            .onFailure { localError = it.message ?: "Не удалось загрузить Telegram-конфиг" }
    }

    LaunchedEffect(state.telegramAuthStep) {
        if (state.telegramAuthStep == TelegramAuthStepUi.CONNECTED) {
            codeValue = ""
            passwordValue = ""
            localError = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Telegram User Client",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )

        val hint = when (state.telegramAuthStep) {
            TelegramAuthStepUi.PHONE -> "Шаг 1: укажите номер телефона"
            TelegramAuthStepUi.CODE -> "Шаг 2: введите код из Telegram"
            TelegramAuthStepUi.PASSWORD -> "Шаг 3: введите пароль 2FA (если включен)"
            TelegramAuthStepUi.CONNECTED -> "Подключение активно"
            TelegramAuthStepUi.LOGGING_OUT -> "Завершаем сессию..."
            TelegramAuthStepUi.INITIALIZING -> "Подключаем Telegram клиент..."
            TelegramAuthStepUi.ERROR -> "Проверьте данные и повторите вход"
        }

        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        when (state.telegramAuthStep) {
            TelegramAuthStepUi.PHONE,
            TelegramAuthStepUi.ERROR,
            TelegramAuthStepUi.INITIALIZING -> {
                LabeledTextField(
                    label = "Phone Number",
                    value = phoneValue,
                    onValueChange = {
                        phoneValue = it
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val trimmed = phoneValue.trim()
                        if (trimmed.isBlank()) {
                            localError = "Введите номер телефона"
                            return@Button
                        }
                        scope.launch {
                            runCatching { telegramService.submitPhoneNumber(trimmed) }
                                .onFailure { localError = it.message ?: "Не удалось запросить код Telegram" }
                        }
                    },
                    enabled = !state.telegramAuthBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Request Code")
                }
            }

            TelegramAuthStepUi.CODE -> {
                OutlinedTextField(
                    value = codeValue,
                    onValueChange = {
                        codeValue = it
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    label = { Text("Login Code") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
                Button(
                    onClick = {
                        val trimmed = codeValue.trim()
                        if (trimmed.isBlank()) {
                            localError = "Введите код входа"
                            return@Button
                        }
                        scope.launch {
                            runCatching { telegramService.submitLoginCode(trimmed) }
                                .onFailure { localError = it.message ?: "Не удалось подтвердить код" }
                                .onSuccess {
                                    codeValue = ""
                                }
                        }
                    },
                    enabled = !state.telegramAuthBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Verify Code")
                }
            }

            TelegramAuthStepUi.PASSWORD -> {
                OutlinedTextField(
                    value = passwordValue,
                    onValueChange = {
                        passwordValue = it
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("2FA Password") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
                Button(
                    onClick = {
                        if (passwordValue.isBlank()) {
                            localError = "Введите пароль 2FA"
                            return@Button
                        }
                        scope.launch {
                            runCatching { telegramService.submitTwoFaPassword(passwordValue) }
                                .onFailure { localError = it.message ?: "Не удалось подтвердить пароль 2FA" }
                                .onSuccess {
                                    passwordValue = ""
                                }
                        }
                    },
                    enabled = !state.telegramAuthBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Verify Password")
                }
            }

            TelegramAuthStepUi.CONNECTED -> {
                Text(
                    text = "Active Session: ${state.telegramActiveSessionPhone ?: "Connected"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { telegramService.logout() }
                                .onFailure { localError = it.message ?: "Не удалось завершить Telegram-сессию" }
                        }
                    },
                    enabled = !state.telegramAuthBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                ) {
                    Text("Logout")
                }
            }

            TelegramAuthStepUi.LOGGING_OUT -> {
                CircularProgressIndicator()
            }
        }

        state.telegramCodeHint?.takeIf { it.isNotBlank() }?.let { codeHint ->
            Text(
                text = "Код отправлен на: $codeHint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }

        state.telegramPasswordHint?.takeIf { it.isNotBlank() }?.let { passwordHint ->
            Text(
                text = "Подсказка пароля: $passwordHint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }

        val errorText = localError?.takeIf { it.isNotBlank() } ?: state.telegramAuthError?.takeIf { it.isNotBlank() }
        errorText?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        localInfo?.takeIf { it.isNotBlank() }?.let { info ->
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        OutlinedButton(
            onClick = { advancedExpanded = !advancedExpanded },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                if (advancedExpanded) "Скрыть расширенные параметры" else "Расширенные параметры Telegram",
            )
        }

        if (advancedExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Встроенные Telegram credentials поставляются с приложением.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )

                OutlinedButton(
                    onClick = { debugLogsEnabled = !debugLogsEnabled },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    RowLikeCheckbox(
                        checked = debugLogsEnabled,
                        text = "Debug-логи Telegram",
                    )
                }

                Button(
                    onClick = {
                        localError = null
                        localInfo = null
                        scope.launch {
                            runCatching {
                                telegramService.updateClientConfig(
                                    debugLogsEnabled = debugLogsEnabled,
                                )
                            }
                                .onSuccess {
                                    localInfo = "Telegram-конфиг применен. Клиент перезапущен."
                                    reloadClientConfig()
                                }
                                .onFailure { localError = it.message ?: "Не удалось применить Telegram-конфиг" }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Применить и перезапустить Telegram")
                }
            }
        }
    }
}

@Composable
private fun RowLikeCheckbox(
    checked: Boolean,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
