package ru.gigadesk.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.kodein.di.compose.localDI
import org.kodein.di.instance
import ru.gigadesk.service.telegram.TelegramService
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import ru.gigadesk.ui.components.LabeledTextField
import gigadesk.composeapp.generated.resources.Res
import gigadesk.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

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

    val errorEnterPhone = stringResource(Res.string.error_enter_phone)
    val errorEnterCode = stringResource(Res.string.error_enter_code)
    val errorEnterPassword = stringResource(Res.string.error_enter_password)
    val errorFailedRequestCode = stringResource(Res.string.error_failed_request_code)
    val errorFailedVerifyCode = stringResource(Res.string.error_failed_verify_code)
    val errorFailedVerifyPassword = stringResource(Res.string.error_failed_verify_password)
    val errorFailedLogout = stringResource(Res.string.error_failed_logout)

    LaunchedEffect(state.telegramAuthStep) {
        if (state.telegramAuthStep == TelegramAuthStepUi.CONNECTED) {
            codeValue = ""
            passwordValue = ""
            localError = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val hint = when (state.telegramAuthStep) {
            TelegramAuthStepUi.PHONE -> stringResource(Res.string.telegram_step_phone)
            TelegramAuthStepUi.CODE -> stringResource(Res.string.telegram_step_code)
            TelegramAuthStepUi.PASSWORD -> stringResource(Res.string.telegram_step_password)
            TelegramAuthStepUi.CONNECTED -> stringResource(Res.string.telegram_step_connected)
            TelegramAuthStepUi.LOGGING_OUT -> stringResource(Res.string.telegram_step_logging_out)
            TelegramAuthStepUi.INITIALIZING -> stringResource(Res.string.telegram_step_initializing)
            TelegramAuthStepUi.ERROR -> stringResource(Res.string.telegram_step_error)
        }

        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        val submitPhone = {
            val trimmed = phoneValue.trim()
            if (trimmed.isBlank()) {
                localError = errorEnterPhone
            } else {
                scope.launch {
                    runCatching { telegramService.submitPhoneNumber(trimmed) }
                        .onFailure {
                            localError = it.message ?: errorFailedRequestCode
                        }
                }
            }
        }

        val submitCode = {
            val trimmed = codeValue.trim()
            if (trimmed.isBlank()) {
                localError = errorEnterCode
            } else {
                scope.launch {
                    runCatching { telegramService.submitLoginCode(trimmed) }
                        .onFailure {
                            localError = it.message ?: errorFailedVerifyCode
                        }
                        .onSuccess {
                            codeValue = ""
                        }
                }
            }
        }

        val submitPassword = {
            if (passwordValue.isBlank()) {
                localError = errorEnterPassword
            } else {
                scope.launch {
                    runCatching { telegramService.submitTwoFaPassword(passwordValue) }
                        .onFailure {
                            localError = it.message ?: errorFailedVerifyPassword
                        }
                        .onSuccess {
                            passwordValue = ""
                        }
                }
            }
        }

        when (state.telegramAuthStep) {
            TelegramAuthStepUi.PHONE,
            TelegramAuthStepUi.ERROR,
            TelegramAuthStepUi.INITIALIZING -> {
                LabeledTextField(
                    label = stringResource(Res.string.telegram_label_phone),
                    value = phoneValue,
                    onValueChange = { newValue ->
                        val filtered = newValue.filter { it.isDigit() || it == '+' }
                        phoneValue = filtered
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                            submitPhone()
                            true
                        } else {
                            false
                        }
                    },
                )
                Button(
                    onClick = { submitPhone() },
                    enabled = !state.telegramAuthBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(Res.string.telegram_btn_request_code))
                }
            }

            TelegramAuthStepUi.CODE -> {
                OutlinedTextField(
                    value = codeValue,
                    onValueChange = {
                        codeValue = it
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                            submitCode()
                            true
                        } else {
                            false
                        }
                    },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    label = { Text(stringResource(Res.string.telegram_label_code)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
                Button(
                    onClick = { submitCode() },
                    enabled = !state.telegramAuthBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(Res.string.telegram_btn_verify_code))
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { telegramService.cancelAuth() }
                                .onFailure { localError = it.message }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Back")
                }
            }

            TelegramAuthStepUi.PASSWORD -> {
                OutlinedTextField(
                    value = passwordValue,
                    onValueChange = {
                        passwordValue = it
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                            submitPassword()
                            true
                        } else {
                            false
                        }
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(Res.string.telegram_label_password)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
                Button(
                    onClick = { submitPassword() },
                    enabled = !state.telegramAuthBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(Res.string.telegram_btn_verify_password))
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { telegramService.cancelAuth() }
                                .onFailure { localError = it.message }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Back")
                }
            }

            TelegramAuthStepUi.CONNECTED -> {
                Text(
                    text = state.telegramActiveSessionPhone ?: stringResource(Res.string.telegram_status_connected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                        runCatching { telegramService.logout() }
                                .onFailure {
                                    localError = it.message ?: errorFailedLogout
                                }
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
                    Text(stringResource(Res.string.telegram_btn_logout))
                }
            }

            TelegramAuthStepUi.LOGGING_OUT -> {
                CircularProgressIndicator()
            }
        }

        state.telegramCodeHint?.takeIf { it.isNotBlank() }?.let { codeHint ->
            Text(
                text = stringResource(Res.string.telegram_hint_code_sent).format(codeHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }

        state.telegramPasswordHint?.takeIf { it.isNotBlank() }?.let { passwordHint ->
            Text(
                text = stringResource(Res.string.telegram_hint_password).format(passwordHint),
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
    }
}
