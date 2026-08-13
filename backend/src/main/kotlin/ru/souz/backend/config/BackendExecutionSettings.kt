package ru.souz.backend.config

import java.util.Locale
import ru.souz.agent.AgentId
import ru.souz.agent.spi.AgentRuntimeSettings
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.db.REGION_EN
import ru.souz.db.REGION_RU
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.ProviderSettings
import ru.souz.llms.findLLMModel

/** Mutable request state owned by one backend conversation execution. */
class BackendExecutionSettings(
    private val deployment: BackendSettingsConfig,
    private val defaultSystemPrompt: String,
    locale: String,
    useFewShotExamples: Boolean = deployment.useFewShotExamples,
    requestTimeoutMillis: Long = deployment.requestTimeoutMillis,
) : AgentRuntimeSettings, ProviderSettings {
    private var systemPrompt: String? = null

    override val defaultCalendar: String? = deployment.defaultCalendar
    override var regionProfile: String = locale.toRegionProfile()
        private set
    override val activeAgentId: AgentId = AgentId.SKILLS_GRAPH
    override var gigaModel: LLMModel = deployment.gigaModel
        private set
    override var useStreaming: Boolean = false
        private set
    override var contextSize: Int = deployment.contextSize
        private set
    override var temperature: Float = deployment.temperature
        private set
    override val embeddingsModel: EmbeddingsModel = deployment.embeddingsModel
    override val openaiBaseUrl: String? = deployment.openaiBaseUrl
    override val openaiModel: String? = deployment.openaiModel
    override var requestTimeoutMillis: Long = requestTimeoutMillis
        private set
    var useFewShotExamples: Boolean = useFewShotExamples
        private set

    override fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String =
        systemPrompt ?: defaultSystemPrompt

    fun restore(temperature: Float, locale: String) {
        this.temperature = temperature
        regionProfile = locale.toRegionProfile()
    }

    internal fun applyRequest(request: BackendConversationTurnRequest, temperature: Float) {
        gigaModel = findLLMModel(request.model) ?: deployment.gigaModel
        contextSize = request.contextSize
        this.temperature = request.temperature ?: temperature
        regionProfile = request.locale.toRegionProfile()
        systemPrompt = request.systemPrompt
        useStreaming = request.streamingMessages == true
        useFewShotExamples = request.useFewShotExamples ?: useFewShotExamples
        requestTimeoutMillis = request.requestTimeoutMillis ?: requestTimeoutMillis
    }
}

private fun String.toRegionProfile(): String {
    val language = runCatching { Locale.forLanguageTag(this).language.lowercase() }
        .getOrDefault("")
    return if (language == REGION_EN) REGION_EN else REGION_RU
}
