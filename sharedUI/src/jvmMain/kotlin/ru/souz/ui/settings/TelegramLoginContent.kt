package ru.souz.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.souz.ui.components.LabeledTextField
import ru.souz.ui.components.SettingsActionButton
import ru.souz.ui.components.SettingsActionStyle
import ru.souz.ui.souzColors
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.*

private fun Modifier.submitOnEnter(
    enabled: Boolean,
    onSubmit: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val isSubmit = event.type == KeyEventType.KeyDown &&
        (event.key == Key.Enter || event.key == Key.NumPadEnter)
    if (isSubmit && enabled) onSubmit()
    isSubmit
}

@Composable
fun TelegramLoginContent(
    state: SettingsState,
    onEvent: (SettingsEvent) -> Unit,
    onStartWork: () -> Unit,
) {
    val authEnabled = !state.telegramAuthBusy && !state.telegramOperationBusy

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
            color = MaterialTheme.souzColors.settings.secondaryContent,
        )

        when (state.telegramAuthStep) {
            TelegramAuthStepUi.PHONE,
            TelegramAuthStepUi.INITIALIZING,
            TelegramAuthStepUi.ERROR -> {
                LabeledTextField(
                    label = stringResource(Res.string.telegram_label_phone),
                    value = state.telegramPhoneInput,
                    onValueChange = { onEvent(SettingsEvent.InputTelegramPhone(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .submitOnEnter(authEnabled) { onEvent(SettingsEvent.SubmitTelegramPhone) },
                )
                SettingsActionButton(
                    text = stringResource(Res.string.telegram_btn_request_code),
                    onClick = { onEvent(SettingsEvent.SubmitTelegramPhone) },
                    enabled = authEnabled,
                    style = SettingsActionStyle.PRIMARY,
                )
            }

            TelegramAuthStepUi.CODE -> {
                LabeledTextField(
                    label = stringResource(Res.string.telegram_label_code),
                    value = state.telegramCodeInput,
                    onValueChange = { onEvent(SettingsEvent.InputTelegramCode(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .submitOnEnter(authEnabled) { onEvent(SettingsEvent.SubmitTelegramCode) },
                )
                SettingsActionButton(
                    text = stringResource(Res.string.telegram_btn_verify_code),
                    onClick = { onEvent(SettingsEvent.SubmitTelegramCode) },
                    enabled = authEnabled,
                    style = SettingsActionStyle.PRIMARY,
                )
                SettingsActionButton(
                    text = stringResource(Res.string.telegram_btn_request_code_again),
                    onClick = { onEvent(SettingsEvent.RequestTelegramCodeAgain) },
                    enabled = authEnabled,
                    style = SettingsActionStyle.SECONDARY,
                )
            }

            TelegramAuthStepUi.PASSWORD -> {
                LabeledTextField(
                    label = stringResource(Res.string.telegram_label_password),
                    value = state.telegramPasswordInput,
                    onValueChange = { onEvent(SettingsEvent.InputTelegramPassword(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .submitOnEnter(authEnabled) { onEvent(SettingsEvent.SubmitTelegramPassword) },
                    visualTransformation = PasswordVisualTransformation(),
                )
                SettingsActionButton(
                    text = stringResource(Res.string.telegram_btn_verify_password),
                    onClick = { onEvent(SettingsEvent.SubmitTelegramPassword) },
                    enabled = authEnabled,
                    style = SettingsActionStyle.PRIMARY,
                )
            }

            TelegramAuthStepUi.CONNECTED -> {
                Text(
                    text = state.telegramActiveSessionPhone ?: stringResource(Res.string.telegram_status_connected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.souzColors.settings.content,
                )
                SettingsActionButton(
                    text = stringResource(
                        if (state.isTelegramBotActive) {
                            Res.string.telegram_btn_delete_control
                        } else {
                            Res.string.telegram_btn_create_control
                        }
                    ),
                    onClick = {
                        onEvent(
                            if (state.isTelegramBotActive) {
                                SettingsEvent.DisconnectTelegramBot
                            } else {
                                SettingsEvent.CreateControlBot
                            }
                        )
                    },
                    enabled = authEnabled,
                    style = if (state.isTelegramBotActive) {
                        SettingsActionStyle.DESTRUCTIVE
                    } else {
                        SettingsActionStyle.SECONDARY
                    },
                )
                SettingsActionButton(
                    text = stringResource(Res.string.telegram_btn_start_work),
                    onClick = onStartWork,
                    enabled = authEnabled,
                    style = SettingsActionStyle.PRIMARY,
                )
                SettingsActionButton(
                    text = stringResource(Res.string.telegram_btn_logout),
                    onClick = { onEvent(SettingsEvent.TelegramLogout) },
                    enabled = authEnabled,
                    style = SettingsActionStyle.DESTRUCTIVE,
                )
            }

            TelegramAuthStepUi.LOGGING_OUT -> {
                CircularProgressIndicator(color = MaterialTheme.souzColors.settings.content)
            }
        }

        if (
            state.telegramAuthStep == TelegramAuthStepUi.ERROR ||
            state.telegramAuthStep == TelegramAuthStepUi.CODE ||
            state.telegramAuthStep == TelegramAuthStepUi.PASSWORD
        ) {
            SettingsActionButton(
                text = stringResource(Res.string.telegram_btn_start_over),
                onClick = { onEvent(SettingsEvent.RestartTelegramAuth) },
                enabled = authEnabled,
            )
        }

        state.telegramCodeHint?.takeIf { it.isNotBlank() }?.let { codeHint ->
            Text(
                text = stringResource(Res.string.telegram_hint_code_sent).format(codeHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.souzColors.settings.secondaryContent,
            )
        }
        state.telegramPasswordHint?.takeIf { it.isNotBlank() }?.let { passwordHint ->
            Text(
                text = stringResource(Res.string.telegram_hint_password).format(passwordHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.souzColors.settings.secondaryContent,
            )
        }
        state.telegramAuthError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.telegramAuthInfo?.takeIf { it.isNotBlank() }?.let { info ->
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.souzColors.settings.content,
            )
        }
    }
}
