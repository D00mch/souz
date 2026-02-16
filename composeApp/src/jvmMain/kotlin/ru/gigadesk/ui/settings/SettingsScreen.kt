package ru.gigadesk.ui.settings

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.debounce
import org.kodein.di.compose.localDI
import org.kodein.di.instance
import ru.gigadesk.agent.session.GraphSessionRepository
import ru.gigadesk.ui.AppTheme
import ru.gigadesk.ui.glassColors
import ru.gigadesk.ui.graphlog.GraphSessionsScreen
import ru.gigadesk.ui.graphlog.GraphVisualizationScreen
import ru.gigadesk.ui.main.RealLiquidGlassCard
import ru.gigadesk.ui.common.DraggableWindowArea
import ru.gigadesk.ui.common.DraggableWindowArea
import ru.gigadesk.ui.common.applyMinWindowSize
import gigadesk.composeapp.generated.resources.Res
import gigadesk.composeapp.generated.resources.*
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
    val sessionRepository: GraphSessionRepository by di.instance()

    LaunchedEffect(viewModel) {
        @Suppress("OPT_IN_USAGE")
        viewModel.effects.debounce(2000).collect { effect ->
            when (effect) {
                SettingsEffect.CloseScreen -> onClose()
                SettingsEffect.NotifyOnSystemPrompt -> onShowSnack(getString(Res.string.snack_saved_system_prompt))
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.send(SettingsEvent.RefreshFromProvider)
    }

    val windowScope = ru.gigadesk.LocalWindowScope.current
    DisposableEffect(windowScope) {
        val window = windowScope?.window
        val originalMinSize = window?.let { applyMinWindowSize(it, minWidth = 680, minHeight = 700) }
        onDispose { window?.minimumSize = originalMinSize }
    }
    
    when (state.currentScreen) {
        SettingsSubScreen.MAIN -> {
            SettingsScreenMain(
                state = state,
                viewModel = viewModel,
                onClose = onClose,
                onOpenTools = onOpenTools,
                onShowSnack = onShowSnack
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
                    Text(stringResource(Res.string.error_session_not_found), color = Color.White)
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
                onStartWork = onClose,
            )
        }
    }
}

@Composable
fun SettingsScreenMain(
    state: SettingsState,
    viewModel: SettingsViewModel,
    onClose: () -> Unit,
    onOpenTools: () -> Unit,
    onShowSnack: (String) -> Unit
) {
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused
    val clipboardManager = LocalClipboardManager.current

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = isFocused
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Sidebar
                Column(modifier = Modifier.width(280.dp).fillMaxHeight()) {
                     DraggableWindowArea {
                        SettingsSidebar(
                            activeSection = state.activeSection,
                            onSectionSelected = { viewModel.send(SettingsEvent.SelectSettingsSection(it)) },
                            onClose = onClose,
                            modifier = Modifier.fillMaxWidth()
                        )
                     }
                }
               
               // Vertical divider
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .padding(vertical = 24.dp)
                        .background(MaterialTheme.glassColors.textPrimary.copy(alpha = 0.15f))
                )

                // Content Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(
                            RoundedCornerShape(
                                topStart = 0.dp,
                                bottomStart = 0.dp,
                                topEnd = 22.dp,
                                bottomEnd = 22.dp
                            )
                        )
                ) {
                    when (state.activeSection) {
                        SettingsSection.MODELS -> ModelsSettingsContent(
                            state = state,
                            onModelChange = { viewModel.send(SettingsEvent.SelectModel(it)) },
                            onEmbeddingsModelChange = { viewModel.send(SettingsEvent.SelectEmbeddingsModel(it)) },
                            onTemperatureInput = { viewModel.send(SettingsEvent.InputTemperature(it)) },
                            onRequestTimeoutMillisChange = { viewModel.send(SettingsEvent.InputRequestTimeoutMillis(it)) },
                            onContextSizeInput = { viewModel.send(SettingsEvent.InputContextSize(it)) },
                            onSystemPromptChange = { viewModel.send(SettingsEvent.InputSystemPrompt(it)) },
                            onSystemPromptReset = { viewModel.send(SettingsEvent.ResetSystemPrompt) },
                            onRefreshBalance = { viewModel.send(SettingsEvent.RefreshBalance) },
                            onClose = onClose
                        )
                        SettingsSection.GENERAL -> GeneralSettingsContent(
                            state = state,
                            onDefaultCalendarChange = { viewModel.send(SettingsEvent.SelectDefaultCalendar(it)) },
                            onUseStreamingChange = { viewModel.send(SettingsEvent.InputUseStreaming(it)) },
                            onVoiceSpeedInput = { viewModel.send(SettingsEvent.InputVoiceSpeed(it)) },
                            onChooseVoice = { viewModel.send(SettingsEvent.ChooseVoice) },
                            onMcpServersJsonInput = { viewModel.send(SettingsEvent.InputMcpServersJson(it)) },
                            onClose = onClose
                        )
                        SettingsSection.KEYS -> KeysSettingsContent(
                            state = state,
                            onGigaChatKeyInput = { viewModel.send(SettingsEvent.InputGigaChatKey(it)) },
                            onQwenChatKeyInput = { viewModel.send(SettingsEvent.InputQwenChatKey(it)) },
                            onAiTunnelKeyInput = { viewModel.send(SettingsEvent.InputAiTunnelKey(it)) },
                            onSaluteSpeechKeyInput = { viewModel.send(SettingsEvent.InputSaluteSpeechKey(it)) },
                            onOpenProviderLink = { viewModel.send(SettingsEvent.OpenProviderLink(it)) },
                            onClose = onClose
                        )
                        SettingsSection.FUNCTIONS -> FunctionsSettingsContent(
                            state = state,
                            onUseFewShotExamplesChange = { viewModel.send(SettingsEvent.InputUseFewShotExamples(it)) },
                            onOpenTools = onOpenTools,
                            onOpenTelegramSettings = { viewModel.send(SettingsEvent.OpenTelegramSettings) },
                            onClose = onClose
                        )
                        SettingsSection.SECURITY -> SecuritySettingsContent(
                            state = state,
                            onSafeModeChange = { viewModel.send(SettingsEvent.InputSafeModeEnabled(it)) },
                            onOpenFoldersManagement = { viewModel.send(SettingsEvent.OpenFoldersManagement) },
                            onClose = onClose
                        )
                        SettingsSection.SUPPORT -> SupportSettingsContent(
                            state = state,
                            onSupportEmailInput = { viewModel.send(SettingsEvent.InputSupportEmail(it)) },
                            onSendLogs = { viewModel.send(SettingsEvent.SendLogsToSupport) },
                            clipboardManager = clipboardManager,
                            onShowSnack = onShowSnack,
                            onOpenGraphSessions = { viewModel.send(SettingsEvent.OpenGraphSessions) },
                            onClose = onClose
                        )
                    }
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
            Row(modifier = Modifier.fillMaxSize()) {
                SettingsSidebar(
                    activeSection = previewState.activeSection,
                    onSectionSelected = {},
                    onClose = {},
                    modifier = Modifier.fillMaxHeight()
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .padding(vertical = 24.dp)
                        .background(MaterialTheme.glassColors.textPrimary.copy(alpha = 0.15f))
                )

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ModelsSettingsContent(
                        state = previewState,
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
        }
    }
}
