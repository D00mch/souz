package ru.souz.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.souz.agent.AgentId
import ru.souz.android.agent.AndroidAgentRuntime
import ru.souz.android.settings.AndroidSettingsProvider
import ru.souz.android.storage.AndroidChatDatabase
import ru.souz.android.storage.AndroidChatMessage
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.ui.sharedchat.SharedChatEvent
import ru.souz.ui.sharedchat.SharedChatMessageUi
import ru.souz.ui.sharedchat.SharedChatRole
import ru.souz.ui.sharedchat.SharedChatScreen
import ru.souz.ui.sharedchat.SharedChatUiState
import ru.souz.ui.sharedsettings.SharedApiKeyFieldUi
import ru.souz.ui.sharedsettings.SharedBalanceItemUi
import ru.souz.ui.sharedsettings.SharedKeysSettingsUiState
import ru.souz.ui.sharedsettings.SharedModelOptionUi
import ru.souz.ui.sharedsettings.SharedModelsSettingsUiState
import ru.souz.ui.sharedsettings.SharedProviderLinkUi
import ru.souz.ui.sharedsettings.SharedSettingsEvent
import ru.souz.ui.sharedsettings.SharedSettingsUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SouzAndroidApp(
    context: Context,
    settings: AndroidSettingsProvider,
    chatDatabase: AndroidChatDatabase,
    agentRuntime: AndroidAgentRuntime,
) {
    val scope = rememberCoroutineScope()
    val adapter = remember(settings, chatDatabase, agentRuntime, scope) {
        AndroidSharedChatAdapter(
            context = context.applicationContext,
            settings = settings,
            chatDatabase = chatDatabase,
            agentRuntime = agentRuntime,
            scope = scope,
        )
    }

    SharedChatScreen(
        state = adapter.uiState,
        onEvent = adapter::onEvent,
    )
}

private class AndroidSharedChatAdapter(
    private val context: Context,
    private val settings: AndroidSettingsProvider,
    private val chatDatabase: AndroidChatDatabase,
    private val agentRuntime: AndroidAgentRuntime,
    private val scope: CoroutineScope,
) {
    private var gigaChatKey by mutableStateOf(settings.gigaChatKey.orEmpty())
    private var qwenChatKey by mutableStateOf(settings.qwenChatKey.orEmpty())
    private var aiTunnelKey by mutableStateOf(settings.aiTunnelKey.orEmpty())
    private var anthropicKey by mutableStateOf(settings.anthropicKey.orEmpty())
    private var openaiKey by mutableStateOf(settings.openaiKey.orEmpty())
    private var saluteSpeechKey by mutableStateOf(settings.saluteSpeechKey.orEmpty())
    private var selectedChatModelAlias by mutableStateOf(settings.chatModelAlias)
    private var selectedEmbeddingsModelAlias by mutableStateOf(settings.embeddingsModel.alias)
    private var selectedVoiceModelAlias by mutableStateOf(settings.voiceRecognitionModel.alias)
    private var temperatureInput by mutableStateOf(settings.temperature.toString())
    private var timeoutInput by mutableStateOf(settings.requestTimeoutMillis.toString())
    private var contextSizeInput by mutableStateOf(settings.contextSize.toString())
    private var systemPromptInput by mutableStateOf(
        settings.getSystemPromptForAgentModel(AgentId.default, settings.gigaModel).orEmpty(),
    )
    private var input by mutableStateOf("")
    private var status by mutableStateOf<String?>(null)
    private var isSending by mutableStateOf(false)
    private var messages by mutableStateOf(chatDatabase.listMessages())

    val uiState: SharedChatUiState
        get() = SharedChatUiState(
            input = input,
            messages = messages.map(AndroidChatMessage::toSharedUi),
            status = status,
            isSending = isSending,
            title = "Souz Android",
            emptyText = "Add an API key, save settings, and send a message.",
            inputPlaceholder = "Message",
            sendLabel = "Send",
            sendingLabel = "Sending",
            stopLabel = "Stop",
            clearLabel = "Clear",
            thinkingLabel = "Thinking",
            canAttach = false,
            canUseVoice = false,
            settings = SharedSettingsUiState(
                models = modelsState(),
                keys = keysState(),
                saveLabel = "Save",
                status = status,
                showSaveAction = true,
            ),
        )

    fun onEvent(event: SharedChatEvent) {
        when (event) {
            is SharedChatEvent.InputChanged -> input = event.value
            SharedChatEvent.SendMessage -> sendMessage()
            SharedChatEvent.CancelProcessing -> {
                agentRuntime.agentFacade.clearContext()
                isSending = false
                status = "Request cancelled"
            }
            SharedChatEvent.ClearConversation -> clearConversation()
            SharedChatEvent.PickAttachments -> Unit
            is SharedChatEvent.RemoveAttachment -> Unit
            SharedChatEvent.StartListening -> Unit
            SharedChatEvent.StopListening -> Unit
            is SharedChatEvent.Settings -> onSettingsEvent(event.event)
        }
    }

    private fun onSettingsEvent(event: SharedSettingsEvent) {
        when (event) {
            is SharedSettingsEvent.SelectChatModel -> {
                selectedChatModelAlias = event.id
                modelFromAlias(event.id)?.let { settings.gigaModel = it }
            }
            is SharedSettingsEvent.SelectEmbeddingsModel -> {
                selectedEmbeddingsModelAlias = event.id
                embeddingsModelFromAlias(event.id)?.let { settings.embeddingsModel = it }
            }
            is SharedSettingsEvent.SelectVoiceModel -> {
                selectedVoiceModelAlias = event.id
                voiceModelFromAlias(event.id)?.let { settings.voiceRecognitionModel = it }
            }
            is SharedSettingsEvent.TemperatureChanged -> temperatureInput = event.value
            is SharedSettingsEvent.TimeoutChanged -> timeoutInput = event.value
            is SharedSettingsEvent.ContextSizeChanged -> contextSizeInput = event.value
            is SharedSettingsEvent.SystemPromptChanged -> systemPromptInput = event.value
            SharedSettingsEvent.ResetSystemPrompt -> {
                systemPromptInput = ""
                modelFromAlias(selectedChatModelAlias)?.let { model ->
                    settings.setSystemPromptForAgentModel(AgentId.default, model, null)
                }
                status = "System prompt reset"
            }
            SharedSettingsEvent.RefreshBalance -> {
                status = "Balance refresh is not available on Android yet"
            }
            is SharedSettingsEvent.ApiKeyChanged -> updateKeyDraft(event.id, event.value)
            is SharedSettingsEvent.OpenProviderLink -> {
                val url = providerLinks().firstOrNull { it.id == event.id }?.url
                if (url == null) {
                    status = "Provider link unavailable"
                } else {
                    openUrl(url)
                }
            }
            is SharedSettingsEvent.StartAuth -> status = "Account auth is not available on Android yet"
            is SharedSettingsEvent.DisconnectAuth -> status = "Account auth is not available on Android yet"
            is SharedSettingsEvent.CopyAuthCode -> status = "Code: ${event.code}"
            SharedSettingsEvent.Save -> saveSettings()
        }
    }

    private fun saveSettings() {
        settings.gigaChatKey = gigaChatKey
        settings.qwenChatKey = qwenChatKey
        settings.aiTunnelKey = aiTunnelKey
        settings.anthropicKey = anthropicKey
        settings.openaiKey = openaiKey
        settings.saluteSpeechKey = saluteSpeechKey
        modelFromAlias(selectedChatModelAlias)?.let { settings.gigaModel = it }
        embeddingsModelFromAlias(selectedEmbeddingsModelAlias)?.let { settings.embeddingsModel = it }
        voiceModelFromAlias(selectedVoiceModelAlias)?.let { settings.voiceRecognitionModel = it }
        temperatureInput.toFloatOrNull()?.let { settings.temperature = it }
        timeoutInput.toLongOrNull()?.let { settings.requestTimeoutMillis = it }
        contextSizeInput.toIntOrNull()?.let { settings.contextSize = it }
        modelFromAlias(selectedChatModelAlias)?.let { model ->
            settings.setSystemPromptForAgentModel(AgentId.default, model, systemPromptInput.takeIf { it.isNotBlank() })
        }
        status = "Settings saved"
    }

    private fun clearConversation() {
        chatDatabase.clear()
        agentRuntime.agentFacade.clearContext()
        messages = emptyList()
        status = "Conversation cleared"
    }

    private fun sendMessage() {
        val text = input.trim()
        if (text.isEmpty() || isSending) return
        input = ""
        isSending = true
        status = null
        scope.launch {
            try {
                val userMessage = chatDatabase.appendMessage("user", text)
                messages = messages + userMessage
                val assistantMessage = chatDatabase.appendMessage("assistant", "")
                messages = messages + assistantMessage

                val response = runCatching {
                    agentRuntime.agentFacade.setModel(modelFromAlias(selectedChatModelAlias) ?: settings.gigaModel)
                    agentRuntime.agentFacade.setContextSize(contextSizeInput.toIntOrNull() ?: settings.contextSize)
                    agentRuntime.agentFacade.execute(
                        input = text,
                        toolInvocationMetaOverride = ToolInvocationMeta.localDefault(
                            conversationId = "android-local-chat",
                            requestId = userMessage.id,
                            attributes = mapOf(
                                "userMessageId" to userMessage.id,
                                "assistantMessageId" to assistantMessage.id,
                            ),
                        ),
                    )
                }
                val assistantText = response.getOrElse { error ->
                    "Error: ${error.message ?: error.toString()}"
                }
                chatDatabase.updateMessageContent(assistantMessage.id, assistantText)
                messages = chatDatabase.listMessages()
                status = response.exceptionOrNull()?.message
            } finally {
                isSending = false
            }
        }
    }

    private fun modelsState(): SharedModelsSettingsUiState {
        val chatOptions = LLMModel.entries
            .filter { hasDraftKey(it.provider) }
            .map { it.toSharedOption(includeAlias = true) }
            .ensureSelected(selectedChatModelAlias, modelFromAlias(selectedChatModelAlias)?.toSharedOption(includeAlias = true))
            .ifEmpty { listOf(LLMModel.OpenAIGpt5Nano.toSharedOption(includeAlias = true)) }
        val embeddingsOptions = EmbeddingsModel.entries
            .filter { hasDraftKey(it.provider) }
            .map { it.toSharedOption() }
            .ensureSelected(selectedEmbeddingsModelAlias, embeddingsModelFromAlias(selectedEmbeddingsModelAlias)?.toSharedOption())
        val voiceOptions = VoiceRecognitionModel.entries
            .filter { hasDraftVoiceKey(it.provider) }
            .map { it.toSharedOption() }
            .ensureSelected(selectedVoiceModelAlias, voiceModelFromAlias(selectedVoiceModelAlias)?.toSharedOption())

        return SharedModelsSettingsUiState(
            selectedChatModelId = selectedChatModelAlias,
            chatModelOptions = chatOptions,
            selectedEmbeddingsModelId = selectedEmbeddingsModelAlias,
            embeddingsModelOptions = embeddingsOptions,
            selectedVoiceModelId = selectedVoiceModelAlias,
            voiceModelOptions = voiceOptions,
            temperatureInput = temperatureInput,
            timeoutInput = timeoutInput,
            contextSizeInput = contextSizeInput,
            systemPrompt = systemPromptInput,
            showBalance = false,
            balance = emptyList<SharedBalanceItemUi>(),
        )
    }

    private fun keysState(): SharedKeysSettingsUiState =
        SharedKeysSettingsUiState(
            configuredCountText = "Configured keys: ${configuredKeysCount()}",
            chatHint = "One chat key is enough: OpenAI, Qwen, GigaChat, AI Tunnel, or Anthropic.",
            voiceHint = "Voice recognition uses OpenAI, AI Tunnel, or SaluteSpeech keys when available.",
            keyFields = listOf(
                SharedApiKeyFieldUi(KEY_GIGA, "GigaChat Key", gigaChatKey),
                SharedApiKeyFieldUi(KEY_QWEN, "Qwen Key", qwenChatKey),
                SharedApiKeyFieldUi(KEY_AI_TUNNEL, "AI Tunnel Key", aiTunnelKey),
                SharedApiKeyFieldUi(KEY_ANTHROPIC, "Anthropic Key", anthropicKey),
                SharedApiKeyFieldUi(KEY_OPENAI, "OpenAI Key", openaiKey),
                SharedApiKeyFieldUi(KEY_SALUTE_SPEECH, "SaluteSpeech Key", saluteSpeechKey),
            ),
            providerLinks = providerLinks(),
        )

    private fun providerLinks(): List<SharedProviderLinkUi> = listOf(
        SharedProviderLinkUi(
            id = KEY_OPENAI,
            title = "OpenAI Platform",
            url = "https://platform.openai.com/api-keys",
            description = "OpenAI dashboard for managing API keys and usage.",
            details = "Use this key for direct GPT model access.",
        ),
        SharedProviderLinkUi(
            id = KEY_QWEN,
            title = "Alibaba Model Studio (Qwen)",
            url = "https://modelstudio.console.alibabacloud.com/",
            description = "Keys and management for Qwen family models.",
            details = "Suitable for chat and generation on Qwen models.",
        ),
        SharedProviderLinkUi(
            id = KEY_ANTHROPIC,
            title = "Anthropic Console",
            url = "https://console.anthropic.com/settings/keys",
            description = "Anthropic dashboard for managing Claude API keys.",
            details = "Get an API key for direct access to Claude models.",
        ),
        SharedProviderLinkUi(
            id = KEY_AI_TUNNEL,
            title = "AiTunnel",
            url = "https://aitunnel.ru/",
            description = "Unified key for popular foreign models.",
            details = "OpenAI, Anthropic, and Grok models available.",
        ),
        SharedProviderLinkUi(
            id = KEY_GIGA,
            title = "Sber Studio (GigaChat + Speech)",
            url = "https://developers.sber.ru/studio/workspaces",
            description = "Cabinet for GigaChat and SaluteSpeech keys.",
            details = "If planning voice commands, get Speech API key here.",
        ),
    )

    private fun updateKeyDraft(id: String, value: String) {
        when (id) {
            KEY_GIGA -> gigaChatKey = value
            KEY_QWEN -> qwenChatKey = value
            KEY_AI_TUNNEL -> aiTunnelKey = value
            KEY_ANTHROPIC -> anthropicKey = value
            KEY_OPENAI -> openaiKey = value
            KEY_SALUTE_SPEECH -> saluteSpeechKey = value
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { error ->
            status = "Could not open link: ${error.message ?: url}"
        }
    }

    private fun configuredKeysCount(): Int =
        listOf(gigaChatKey, qwenChatKey, aiTunnelKey, anthropicKey, openaiKey, saluteSpeechKey)
            .count { it.isNotBlank() }

    private fun hasDraftKey(provider: LlmProvider): Boolean = when (provider) {
        LlmProvider.GIGA -> gigaChatKey.isNotBlank()
        LlmProvider.QWEN -> qwenChatKey.isNotBlank()
        LlmProvider.AI_TUNNEL -> aiTunnelKey.isNotBlank()
        LlmProvider.ANTHROPIC -> anthropicKey.isNotBlank()
        LlmProvider.OPENAI -> openaiKey.isNotBlank()
        LlmProvider.CODEX -> false
        LlmProvider.LOCAL -> false
    }

    private fun hasDraftVoiceKey(provider: VoiceRecognitionProvider): Boolean = when (provider) {
        VoiceRecognitionProvider.SALUTE_SPEECH -> saluteSpeechKey.isNotBlank()
        VoiceRecognitionProvider.AI_TUNNEL -> aiTunnelKey.isNotBlank()
        VoiceRecognitionProvider.OPENAI -> openaiKey.isNotBlank()
    }

    private companion object {
        const val KEY_GIGA = "giga"
        const val KEY_QWEN = "qwen"
        const val KEY_AI_TUNNEL = "aitunnel"
        const val KEY_ANTHROPIC = "anthropic"
        const val KEY_OPENAI = "openai"
        const val KEY_SALUTE_SPEECH = "salutespeech"
    }
}

private fun AndroidChatMessage.toSharedUi(): SharedChatMessageUi =
    SharedChatMessageUi(
        id = id,
        role = when (role) {
            "user" -> SharedChatRole.USER
            "assistant" -> SharedChatRole.ASSISTANT
            else -> SharedChatRole.SYSTEM
        },
        content = content,
        timestampText = TimestampFormatter.format(Date(createdAt)),
    )

private fun LLMModel.toSharedOption(includeAlias: Boolean): SharedModelOptionUi =
    SharedModelOptionUi(
        id = alias,
        label = displayName,
        detail = alias.takeIf { includeAlias },
    )

private fun EmbeddingsModel.toSharedOption(): SharedModelOptionUi =
    SharedModelOptionUi(
        id = alias,
        label = displayName,
    )

private fun VoiceRecognitionModel.toSharedOption(): SharedModelOptionUi =
    SharedModelOptionUi(
        id = alias,
        label = displayName,
    )

private fun modelFromAlias(value: String): LLMModel? =
    LLMModel.entries.firstOrNull { it.alias.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }

private fun embeddingsModelFromAlias(value: String): EmbeddingsModel? =
    EmbeddingsModel.entries.firstOrNull { it.alias.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }

private fun voiceModelFromAlias(value: String): VoiceRecognitionModel? =
    VoiceRecognitionModel.entries.firstOrNull { it.alias.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }

private fun List<SharedModelOptionUi>.ensureSelected(
    selectedId: String,
    selectedOption: SharedModelOptionUi?,
): List<SharedModelOptionUi> =
    if (any { it.id == selectedId } || selectedOption == null) this else listOf(selectedOption) + this

private val TimestampFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
