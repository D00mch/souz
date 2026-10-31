package ru.souz.backend.llm

import ru.souz.backend.config.NamedLlmConfig
import ru.souz.backend.hooks.HookLlmBudget
import ru.souz.backend.hooks.HookLlmBudget.Companion.withProviderCall
import ru.souz.db.SettingsProvider
import ru.souz.llms.LlmProvider
import ru.souz.llms.findLLMModel
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.codex.CodexImageGenerationGateway
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.openai.OpenAIImageGenerationGateway
import ru.souz.llms.runtime.ImageGenerationGateway

/** Resolves one image model for both output destinations using the existing connections and credentials. */
internal fun imageGateway(selection: String, models: NamedLlmConfig, settings: SettingsProvider,
    clients: ProviderHttpClients, oauth: CodexOAuthService, api: BackendExecutionLlmChatApi,
    budget: HookLlmBudget?): ImageGenerationGateway {
    val named = models.models[selection]
    val builtin = findLLMModel(selection)
    val provider = if (named != null || selection == "gpt-image-1")
        LlmProvider.OPENAI else requireNotNull(builtin?.takeIf { it.provider == LlmProvider.CODEX }) {
            "Unsupported image model: $selection"
        }.provider
    val model = named?.modelId ?: builtin?.alias ?: selection
    val gateway = if (provider == LlmProvider.CODEX) CodexImageGenerationGateway(settings, oauth, clients.standard)
        else OpenAIImageGenerationGateway(settings, clients.openAi,
            apiKeyProvider = { named?.apiKey ?: api.credentialFor(LlmProvider.OPENAI) }, baseUrl = named?.baseUrl)
    return ImageGenerationGateway { input ->
        withProviderCall(budget, provider) {
            gateway.generate(input.copy(model = model, size = "1024x1024", quality = "medium", outputFormat = "png"))
        }
    }
}
