package ru.souz.ui.sharedsettings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.souz.ui.components.LabeledTextField
import ru.souz.ui.settings.ApiKeyFieldState
import ru.souz.ui.settings.HIDDEN_API_KEY_MASK

data class SharedModelOptionUi(
    val id: String,
    val label: String,
    val detail: String? = null,
)

data class SharedBalanceItemUi(
    val label: String,
    val value: String,
)

data class SharedModelsSettingsUiState(
    val title: String = "Models",
    val subtitle: String = "Models and generation parameters",
    val chatModelLabel: String = "Model",
    val selectedChatModelId: String,
    val chatModelOptions: List<SharedModelOptionUi>,
    val embeddingsModelLabel: String = "Embeddings model",
    val selectedEmbeddingsModelId: String? = null,
    val embeddingsModelOptions: List<SharedModelOptionUi> = emptyList(),
    val voiceModelLabel: String = "Voice recognition model",
    val selectedVoiceModelId: String? = null,
    val voiceModelOptions: List<SharedModelOptionUi> = emptyList(),
    val temperatureLabel: String = "Temperature",
    val temperatureInput: String,
    val timeoutLabel: String = "Timeout (ms)",
    val timeoutInput: String,
    val contextSizeLabel: String = "Context window size",
    val contextSizeInput: String,
    val systemPromptLabel: String = "System prompt",
    val systemPrompt: String,
    val resetSystemPromptLabel: String = "Reset",
    val balanceTitle: String = "Balance",
    val refreshBalanceLabel: String = "Refresh",
    val isBalanceLoading: Boolean = false,
    val balance: List<SharedBalanceItemUi> = emptyList(),
    val balanceError: String? = null,
    val showBalance: Boolean = false,
)

data class SharedApiKeyFieldUi(
    val id: String,
    val label: String,
    val state: ApiKeyFieldState,
)

data class SharedProviderLinkUi(
    val id: String,
    val title: String,
    val url: String,
    val description: String,
    val details: String,
)

sealed interface SharedAuthAccountUiState {
    val id: String
    val title: String
    val description: String
    val connectLabel: String
    val disconnectLabel: String

    data class Idle(
        override val id: String,
        override val title: String,
        override val description: String,
        override val connectLabel: String,
        override val disconnectLabel: String,
    ) : SharedAuthAccountUiState

    data class Connected(
        override val id: String,
        override val title: String,
        override val description: String,
        override val connectLabel: String,
        override val disconnectLabel: String,
        val connectedLabel: String,
    ) : SharedAuthAccountUiState

    data class AwaitingUserCode(
        override val id: String,
        override val title: String,
        override val description: String,
        override val connectLabel: String,
        override val disconnectLabel: String,
        val userCode: String,
        val authUrl: String,
        val copyLabel: String,
        val pollingLabel: String,
    ) : SharedAuthAccountUiState

    data class Error(
        override val id: String,
        override val title: String,
        override val description: String,
        override val connectLabel: String,
        override val disconnectLabel: String,
        val message: String,
    ) : SharedAuthAccountUiState
}

data class SharedKeysSettingsUiState(
    val title: String = "My Keys",
    val subtitle: String = "Keys configuration and quick links",
    val configuredCountText: String,
    val chatHint: String,
    val voiceHint: String,
    val keyFields: List<SharedApiKeyFieldUi>,
    val authAccounts: List<SharedAuthAccountUiState> = emptyList(),
    val providerLinksTitle: String = "Where to get keys",
    val providerLinks: List<SharedProviderLinkUi> = emptyList(),
)

data class SharedSettingsUiState(
    val models: SharedModelsSettingsUiState,
    val keys: SharedKeysSettingsUiState,
    val modelsTabLabel: String = models.title,
    val keysTabLabel: String = keys.title,
    val saveLabel: String = "Save",
    val status: String? = null,
    val showSaveAction: Boolean = false,
)

sealed interface SharedSettingsEvent {
    data class SelectChatModel(val id: String) : SharedSettingsEvent
    data class SelectEmbeddingsModel(val id: String) : SharedSettingsEvent
    data class SelectVoiceModel(val id: String) : SharedSettingsEvent
    data class TemperatureChanged(val value: String) : SharedSettingsEvent
    data class TimeoutChanged(val value: String) : SharedSettingsEvent
    data class ContextSizeChanged(val value: String) : SharedSettingsEvent
    data class SystemPromptChanged(val value: String) : SharedSettingsEvent
    data object ResetSystemPrompt : SharedSettingsEvent
    data object RefreshBalance : SharedSettingsEvent
    data class ApiKeyChanged(val id: String, val value: String) : SharedSettingsEvent
    data class ApiKeyVisibilityToggled(val id: String) : SharedSettingsEvent
    data class OpenProviderLink(val id: String) : SharedSettingsEvent
    data class StartAuth(val id: String) : SharedSettingsEvent
    data class DisconnectAuth(val id: String) : SharedSettingsEvent
    data class CopyAuthCode(val id: String, val code: String) : SharedSettingsEvent
    data object Save : SharedSettingsEvent
}

private val SharedPanelBackground = Color(0x0DFFFFFF)
private val SharedPanelBorder = Color(0x14FFFFFF)
private val SharedTextStrong = Color.White.copy(alpha = 0.9f)
private val SharedTextMuted = Color.White.copy(alpha = 0.62f)
private val SharedControlHeight = 42.dp
private val SharedShape = RoundedCornerShape(12.dp)

@Composable
fun SharedSettingsPanel(
    state: SharedSettingsUiState,
    onEvent: (SharedSettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(SharedSettingsTab.MODELS) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SharedSettingsTabButton(
                text = state.modelsTabLabel,
                selected = selectedTab == SharedSettingsTab.MODELS,
                onClick = { selectedTab = SharedSettingsTab.MODELS },
                modifier = Modifier.weight(1f),
            )
            SharedSettingsTabButton(
                text = state.keysTabLabel,
                selected = selectedTab == SharedSettingsTab.KEYS,
                onClick = { selectedTab = SharedSettingsTab.KEYS },
                modifier = Modifier.weight(1f),
            )
        }

        when (selectedTab) {
            SharedSettingsTab.MODELS -> SharedModelsSettingsContent(
                state = state.models,
                onEvent = onEvent,
            )

            SharedSettingsTab.KEYS -> SharedKeysSettingsContent(
                state = state.keys,
                onEvent = onEvent,
            )
        }

        if (state.showSaveAction) {
            Button(
                onClick = { onEvent(SharedSettingsEvent.Save) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(state.saveLabel)
            }
        }

        state.status?.takeIf { it.isNotBlank() }?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = SharedTextMuted,
            )
        }
    }
}

@Composable
fun SharedModelsSettingsContent(
    state: SharedModelsSettingsUiState,
    onEvent: (SharedSettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SharedSettingsHeading(
            title = state.title,
            subtitle = state.subtitle,
        )

        SharedDropdownField(
            label = state.chatModelLabel,
            selectedId = state.selectedChatModelId,
            options = state.chatModelOptions,
            onSelected = { onEvent(SharedSettingsEvent.SelectChatModel(it)) },
        )

        if (state.embeddingsModelOptions.isNotEmpty() && state.selectedEmbeddingsModelId != null) {
            SharedDropdownField(
                label = state.embeddingsModelLabel,
                selectedId = state.selectedEmbeddingsModelId,
                options = state.embeddingsModelOptions,
                onSelected = { onEvent(SharedSettingsEvent.SelectEmbeddingsModel(it)) },
            )
        }

        if (state.voiceModelOptions.isNotEmpty() && state.selectedVoiceModelId != null) {
            SharedDropdownField(
                label = state.voiceModelLabel,
                selectedId = state.selectedVoiceModelId,
                options = state.voiceModelOptions,
                onSelected = { onEvent(SharedSettingsEvent.SelectVoiceModel(it)) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LabeledTextField(
                label = state.temperatureLabel,
                value = state.temperatureInput,
                onValueChange = { onEvent(SharedSettingsEvent.TemperatureChanged(it)) },
                modifier = Modifier.weight(1f),
            )
            LabeledTextField(
                label = state.timeoutLabel,
                value = state.timeoutInput,
                onValueChange = { onEvent(SharedSettingsEvent.TimeoutChanged(it)) },
                modifier = Modifier.weight(1f),
            )
        }

        LabeledTextField(
            label = state.contextSizeLabel,
            value = state.contextSizeInput,
            onValueChange = { onEvent(SharedSettingsEvent.ContextSizeChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.systemPromptLabel,
                style = MaterialTheme.typography.titleSmall,
                color = SharedTextStrong,
            )
            TextButton(onClick = { onEvent(SharedSettingsEvent.ResetSystemPrompt) }) {
                Text(state.resetSystemPromptLabel)
            }
        }

        LabeledTextField(
            label = "",
            value = state.systemPrompt,
            onValueChange = { onEvent(SharedSettingsEvent.SystemPromptChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            height = 132.dp,
        )

        if (state.showBalance) {
            SharedBalanceSection(
                state = state,
                onRefresh = { onEvent(SharedSettingsEvent.RefreshBalance) },
            )
        }
    }
}

@Composable
fun SharedKeysSettingsContent(
    state: SharedKeysSettingsUiState,
    onEvent: (SharedSettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SharedSettingsHeading(
            title = state.title,
            subtitle = state.subtitle,
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SharedPanelBackground,
            shape = SharedShape,
            border = BorderStroke(1.dp, SharedPanelBorder),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = state.configuredCountText,
                    style = MaterialTheme.typography.titleSmall,
                    color = SharedTextStrong,
                )
                Text(
                    text = state.chatHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = SharedTextMuted,
                )
                Text(
                    text = state.voiceHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = SharedTextMuted,
                )
            }
        }

        state.keyFields.forEach { field ->
            val editable = field.state as? ApiKeyFieldState.Editable
            LabeledTextField(
                label = field.label,
                value = editable?.value ?: HIDDEN_API_KEY_MASK,
                onValueChange = { value ->
                    if (editable != null) onEvent(SharedSettingsEvent.ApiKeyChanged(field.id, value))
                },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (editable?.revealed == false) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                readOnly = editable == null,
                trailingContent = {
                    if (field.state == ApiKeyFieldState.Revealing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = {
                            onEvent(SharedSettingsEvent.ApiKeyVisibilityToggled(field.id))
                        }) {
                            Icon(
                                imageVector = if (editable?.revealed == true) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (editable?.revealed == true) "Hide API key" else "Show API key",
                                tint = SharedTextMuted,
                            )
                        }
                    }
                },
            )
        }

        state.authAccounts.forEach { auth ->
            SharedAuthAccountCard(
                state = auth,
                onEvent = onEvent,
            )
        }

        if (state.providerLinks.isNotEmpty()) {
            HorizontalDivider(color = SharedPanelBorder)
            Text(
                text = state.providerLinksTitle,
                style = MaterialTheme.typography.titleMedium,
                color = SharedTextStrong,
            )
            state.providerLinks.forEach { provider ->
                SharedProviderLinkCard(
                    provider = provider,
                    onOpen = { onEvent(SharedSettingsEvent.OpenProviderLink(provider.id)) },
                )
            }
        }
    }
}

@Composable
private fun SharedSettingsHeading(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = SharedTextStrong,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SharedTextMuted,
            )
        }
    }
}

@Composable
private fun SharedDropdownField(
    label: String,
    selectedId: String,
    options: List<SharedModelOptionUi>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = SharedTextMuted,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                enabled = options.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SharedControlHeight),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SharedPanelBackground,
                    contentColor = SharedTextStrong,
                ),
                border = BorderStroke(1.dp, SharedPanelBorder),
                shape = SharedShape,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selected?.displayLabel().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        ),
                        color = SharedTextStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = SharedTextStrong,
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(Color(0xF21A1A1D), SharedShape)
                    .border(1.dp, SharedPanelBorder, SharedShape),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SharedTextStrong,
                                )
                                if (!option.detail.isNullOrBlank()) {
                                    Text(
                                        text = option.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SharedTextMuted,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelected(option.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedProviderLinkCard(
    provider: SharedProviderLinkUi,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SharedPanelBackground,
        shape = SharedShape,
        border = BorderStroke(1.dp, SharedPanelBorder),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = provider.title,
                style = MaterialTheme.typography.titleSmall,
                color = SharedTextStrong,
            )
            Text(
                text = provider.description,
                style = MaterialTheme.typography.bodySmall,
                color = SharedTextMuted,
            )
            Text(
                text = provider.details,
                style = MaterialTheme.typography.bodySmall,
                color = SharedTextMuted,
            )
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, SharedPanelBorder),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = provider.url,
                    style = MaterialTheme.typography.labelMedium,
                    color = SharedTextStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SharedAuthAccountCard(
    state: SharedAuthAccountUiState,
    onEvent: (SharedSettingsEvent) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SharedPanelBackground,
        shape = SharedShape,
        border = BorderStroke(1.dp, SharedPanelBorder),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleSmall,
                color = SharedTextStrong,
            )
            Text(
                text = state.description,
                style = MaterialTheme.typography.bodySmall,
                color = SharedTextMuted,
            )

            when (state) {
                is SharedAuthAccountUiState.Idle -> {
                    OutlinedButton(
                        onClick = { onEvent(SharedSettingsEvent.StartAuth(state.id)) },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, SharedPanelBorder),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(state.connectLabel, color = SharedTextStrong)
                    }
                }

                is SharedAuthAccountUiState.Connected -> {
                    Text(
                        text = state.connectedLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = SharedTextMuted,
                    )
                    OutlinedButton(
                        onClick = { onEvent(SharedSettingsEvent.DisconnectAuth(state.id)) },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, SharedPanelBorder),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(state.disconnectLabel, color = SharedTextStrong)
                    }
                }

                is SharedAuthAccountUiState.AwaitingUserCode -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = state.userCode,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SharedTextStrong,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { onEvent(SharedSettingsEvent.CopyAuthCode(state.id, state.userCode)) },
                            border = BorderStroke(1.dp, SharedPanelBorder),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(state.copyLabel, color = SharedTextStrong)
                        }
                    }
                    Text(
                        text = state.authUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            onEvent(SharedSettingsEvent.OpenProviderLink(state.id))
                        },
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            text = state.pollingLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = SharedTextMuted,
                        )
                    }
                }

                is SharedAuthAccountUiState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = { onEvent(SharedSettingsEvent.StartAuth(state.id)) },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, SharedPanelBorder),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(state.connectLabel, color = SharedTextStrong)
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedBalanceSection(
    state: SharedModelsSettingsUiState,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SharedPanelBackground,
        shape = SharedShape,
        border = BorderStroke(1.dp, SharedPanelBorder),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.balanceTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = SharedTextStrong,
                )
                TextButton(
                    onClick = onRefresh,
                    enabled = !state.isBalanceLoading,
                ) {
                    Text(state.refreshBalanceLabel)
                }
            }
            when {
                state.isBalanceLoading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Loading", color = SharedTextMuted)
                    }
                }

                !state.balanceError.isNullOrBlank() -> {
                    Text(
                        text = state.balanceError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.balance.isEmpty() -> {
                    Text(
                        text = "",
                        style = MaterialTheme.typography.bodySmall,
                        color = SharedTextMuted,
                    )
                }

                else -> {
                    state.balance.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(item.label, color = SharedTextMuted)
                            Text(item.value, color = SharedTextStrong)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedSettingsTabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .height(36.dp)
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f), shape)
            .border(1.dp, if (selected) Color.White.copy(alpha = 0.24f) else SharedPanelBorder, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) SharedTextStrong else SharedTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

private fun SharedModelOptionUi.displayLabel(): String =
    if (detail.isNullOrBlank()) label else "$label ($detail)"

private enum class SharedSettingsTab {
    MODELS,
    KEYS,
}
