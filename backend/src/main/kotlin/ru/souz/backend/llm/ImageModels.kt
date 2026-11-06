package ru.souz.backend.llm

import ru.souz.backend.common.BackendConfigurationException
import ru.souz.backend.config.NamedLlmConfig
import ru.souz.backend.hooks.HookLlmBudget
import ru.souz.backend.hooks.HookLlmBudget.Companion.withProviderCall
import ru.souz.db.SettingsProvider
import ru.souz.llms.LlmProvider
import ru.souz.llms.findLLMModel
import ru.souz.llms.LLMModel
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.codex.CodexImageGenerationGateway
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.openai.OpenAIImageGenerationGateway
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.supportsImageInput

/** The operator chooses a vision-capable model; an image-capable protocol alone cannot guarantee that. */
internal class VisionModelRoute(val selection: String?, models: NamedLlmConfig) {
    init {
        if (selection != null && selection !in models.models) {
            val model = findLLMModel(selection)
            if (model == null || model == LLMModel.OpenAICompatibleCustom || !model.provider.supportsImageInput()) {
                throw BackendConfigurationException(
                    "Unsupported SOUZ_ATTACHMENTS_VISION_MODEL / souz.attachments.visionModel '$selection'. " +
                        "Use a configured connection name or an OpenAI, Codex or Anthropic model selector.",
                )
            }
        }
    }
}

/** Validated at host startup; credentials and accounting are supplied per execution. */
internal class ImageModelRoute(selection: String, models: NamedLlmConfig) {
    private val named = models.models[selection]
    private val builtin = findLLMModel(selection)
    private val provider = when {
        named != null || selection in OpenAIImageGenerationGateway.SUPPORTED_MODELS -> LlmProvider.OPENAI
        builtin?.provider == LlmProvider.CODEX -> LlmProvider.CODEX
        else -> throw BackendConfigurationException(
            "Unsupported SOUZ_IMAGE_MODEL / souz.image.model '$selection'. " +
                "Use a supported OpenAI image model, a configured connection name or a Codex selector.",
        )
    }
    private val model = named?.modelId ?: builtin?.alias ?: selection

    // Include a named endpoint/model in the paid-operation identity, never its credential.
    val identity: String = selection + ":" + named?.let { it.baseUrl + ":" + it.modelId }.orEmpty()

    fun gateway(
        settings: SettingsProvider,
        clients: ProviderHttpClients,
        oauth: CodexOAuthService,
        api: BackendExecutionLlmChatApi,
        budget: HookLlmBudget?,
    ): ImageGenerationGateway {
        val gateway = if (provider == LlmProvider.CODEX) {
            CodexImageGenerationGateway(settings, oauth, clients.standard)
        } else {
            OpenAIImageGenerationGateway(
                settings,
                clients.openAi,
                apiKeyProvider = { named?.apiKey ?: api.credentialFor(LlmProvider.OPENAI) },
                baseUrl = named?.baseUrl,
            )
        }
        return ImageGenerationGateway { input ->
            withProviderCall(budget, provider) {
                gateway.generate(input.copy(model = model))
            }
        }
    }
}
