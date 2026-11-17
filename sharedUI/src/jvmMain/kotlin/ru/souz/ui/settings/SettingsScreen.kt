package ru.souz.ui.settings

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI
import ru.souz.ui.AppTheme
import ru.souz.ui.souzColors
import ru.souz.ui.graphlog.GraphSessionsScreen
import ru.souz.ui.graphlog.GraphVisualizationScreen
import ru.souz.ui.common.RealLiquidGlassCard
import ru.souz.ui.common.DraggableWindowArea
import ru.souz.ui.common.MAIN_WINDOW_MIN_HEIGHT_PX
import ru.souz.ui.common.MAIN_WINDOW_MIN_WIDTH_PX
import ru.souz.ui.common.applyMinWindowSize
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onOpenTools: () -> Unit,
    onShowSnack: (String) -> Unit = {},
) {
    val di = localDI()
    val viewModel = viewModel { SettingsViewModel(di) }
    val state = viewModel.uiState.collectAsState().value

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.CloseScreen -> onClose()
                SettingsEffect.OpenTools -> onOpenTools()
                SettingsEffect.NotifyOnSystemPrompt -> onShowSnack(getString(Res.string.snack_saved_system_prompt))
                is SettingsEffect.ShowSnackbar -> onShowSnack(effect.message)
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.send(SettingsEvent.RefreshFromProvider)
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(state.currentScreen) {
        focusRequester.requestFocus()
    }

    val windowScope = ru.souz.LocalWindowScope.current
    DisposableEffect(windowScope) {
        val window = windowScope?.window
        val originalMinSize = window?.let {
            applyMinWindowSize(
                it,
                minWidth = MAIN_WINDOW_MIN_WIDTH_PX,
                minHeight = MAIN_WINDOW_MIN_HEIGHT_PX,
            )
        }
        onDispose { window?.minimumSize = originalMinSize }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    viewModel.send(SettingsEvent.EscapePressed)
                    true
                } else {
                    false
                }
            }
    ) {
        when (state.currentScreen) {
            SettingsSubScreen.MAIN -> key(state.useEnglishInterface) {
                SettingsScreenMain(
                    state = state,
                    onEvent = viewModel::send,
                    onShowSnack = onShowSnack
                )
            }
            SettingsSubScreen.SESSIONS -> {
                GraphSessionsScreen(
                    sessions = state.graphSessions,
                    onClose = { viewModel.send(SettingsEvent.BackToSettings) },
                    onSelectSession = { session -> viewModel.send(SettingsEvent.OpenGraphVisualization(session.id)) }
                )
            }
            SettingsSubScreen.VISUALIZATION -> {
                val session = state.selectedSession
                if (session != null) {
                    GraphVisualizationScreen(
                        session = session,
                        onBack = { viewModel.send(SettingsEvent.BackToSessions) }
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(Res.string.error_session_not_found),
                            color = MaterialTheme.souzColors.settings.content,
                        )
                        Button(onClick = { viewModel.send(SettingsEvent.BackToSessions) }) { Text(stringResource(Res.string.button_back)) }
                    }
                }
            }
            SettingsSubScreen.FOLDERS -> {
                FoldersManagementScreen(
                    onClose = { viewModel.send(SettingsEvent.BackToSettings) }
                )
            }
            SettingsSubScreen.TELEGRAM -> {
                TelegramSettingsScreen(
                    state = state,
                    onClose = { viewModel.send(SettingsEvent.BackToSettings) },
                    onEvent = viewModel::send,
                    onStartWork = { viewModel.send(SettingsEvent.GoToMain) },
                    onConfirmDisconnectControlBot = { viewModel.send(SettingsEvent.ConfirmDisconnectTelegramBot) },
                    onCancelDisconnectControlBot = { viewModel.send(SettingsEvent.CancelDisconnectTelegramBot) },
                )
            }
        }
    }
}

@Composable
fun SettingsScreenMain(
    state: SettingsState,
    onEvent: (SettingsEvent) -> Unit,
    onShowSnack: (String) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
            ) {
                DraggableWindowArea {
                    SettingsSidebar(
                        activeSection = state.activeSection,
                        onSectionSelected = { onEvent(SettingsEvent.SelectSettingsSection(it)) },
                        modifier = Modifier.fillMaxHeight()
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.souzColors.settings.sidebarBorder)
                )

                // Content Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp))
                ) {
                    when (state.activeSection) {
                        SettingsSection.MODELS -> ModelsSettingsContent(state, onEvent)
                        SettingsSection.GENERAL -> GeneralSettingsContent(state, onEvent)
                        SettingsSection.KEYS -> KeysSettingsContent(state, onEvent)
                        SettingsSection.FUNCTIONS -> FunctionsSettingsContent(state, onEvent)
                        SettingsSection.SECURITY -> SecuritySettingsContent(state, onEvent)
                        SettingsSection.SUPPORT -> SupportSettingsContent(state, onEvent, onShowSnack = onShowSnack)
                    }
                }

                state.localModelDownloadPrompt?.let { prompt ->
                    ru.souz.ui.common.LocalModelDownloadPromptDialog(
                        prompt = prompt,
                        onConfirm = { onEvent(SettingsEvent.ConfirmLocalModelDownload) },
                        onDismiss = { onEvent(SettingsEvent.CancelLocalModelDownload) },
                    )
                }

                state.localModelDownloadState?.let { downloadState ->
                    ru.souz.ui.common.LocalModelDownloadProgressDialog(
                        state = downloadState,
                        onCancel = { onEvent(SettingsEvent.CancelLocalModelDownload) },
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun SettingsScreenPreview() {
    AppTheme {
        val previewState = SettingsState(activeSection = SettingsSection.MODELS)

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0B0E11)
        ) {
            SettingsScreenMain(
                state = previewState,
                onEvent = {},
                onShowSnack = {},
            )
        }
    }
}
