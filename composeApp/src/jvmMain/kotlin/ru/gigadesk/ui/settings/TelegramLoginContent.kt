package ru.gigadesk.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gigadesk.ui.components.LabeledTextField

@Composable
fun TelegramLoginContent(
    state: SettingsState,
    onPhoneInput: (String) -> Unit,
    onCodeInput: (String) -> Unit,
    onPasswordInput: (String) -> Unit,
    onSubmitPhone: () -> Unit,
    onSubmitCode: () -> Unit,
    onSubmitPassword: () -> Unit,
    onLogout: () -> Unit,
) {
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
                    value = state.telegramPhoneInput,
                    onValueChange = onPhoneInput,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onSubmitPhone,
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
                LabeledTextField(
                    label = "Login Code",
                    value = state.telegramCodeInput,
                    onValueChange = onCodeInput,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onSubmitCode,
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
                LabeledTextField(
                    label = "2FA Password",
                    value = state.telegramPasswordInput,
                    onValueChange = onPasswordInput,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onSubmitPassword,
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
                    onClick = onLogout,
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
                Text(
                    text = "Logging out...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
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

        state.telegramAuthError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
