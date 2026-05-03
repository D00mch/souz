package ru.souz.agent.skills

import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.selection.LlmSkillSelector
import ru.souz.agent.skills.selection.SkillSelector
import ru.souz.agent.skills.validation.LlmSkillValidator
import ru.souz.agent.skills.validation.SkillLlmValidator
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.json.JsonUtils

internal fun productionSkillSelector(
    llmApi: LLMChatAPI,
    settingsProvider: AgentSettingsProvider,
    jsonUtils: JsonUtils,
): SkillSelector = SkillSelector { input ->
    LlmSkillSelector(
        llmApi = llmApi,
        model = settingsProvider.gigaModel.alias,
        jsonUtils = jsonUtils,
    ).select(input)
}

internal fun productionSkillValidator(
    llmApi: LLMChatAPI,
    settingsProvider: AgentSettingsProvider,
    jsonUtils: JsonUtils,
): SkillLlmValidator = SkillLlmValidator { input ->
    LlmSkillValidator(
        llmApi = llmApi,
        model = settingsProvider.gigaModel.alias,
        jsonUtils = jsonUtils,
    ).validate(input)
}

internal fun productionSkillActivationPipeline(
    registryRepository: SkillRegistryRepository,
    llmApi: LLMChatAPI,
    settingsProvider: AgentSettingsProvider,
    jsonUtils: JsonUtils,
): SkillActivationPipeline = SkillActivationPipeline(
    registryRepository = registryRepository,
    selector = productionSkillSelector(
        llmApi = llmApi,
        settingsProvider = settingsProvider,
        jsonUtils = jsonUtils,
    ),
    llmValidator = productionSkillValidator(
        llmApi = llmApi,
        settingsProvider = settingsProvider,
        jsonUtils = jsonUtils,
    ),
)
