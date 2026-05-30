package ru.souz.agent.nodes

import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Node
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.db.StorredData
import ru.souz.db.StorredType
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.toSystemPromptMessage
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Nodes related to local data manipulation.
 * The nodes may update [AgentContext.input] or [AgentContext.history].
 */
internal class NodesCommon(
    private val desktopInfoRepository: AgentDesktopInfoRepository,
    private val settingsProvider: AgentSettingsProvider,
    private val agentToolExecutor: AgentToolExecutor,
    private val defaultBrowserProvider: DefaultBrowserProvider,
    private val runtimeEnvironment: AgentRuntimeEnvironment,
) {
    private val l = LoggerFactory.getLogger(NodesCommon::class.java)

    /**
     * Ensures proper history with user input as a message.
     *
     * Modifies [AgentContext.history] while preserving [AgentContext.input].
     */
    fun inputToHistory(name: String = "Input->History"): Node<String, String> =
        Node(name) { ctx ->
            val usrMsg = LLMRequest.Message(LLMMessageRole.user, ctx.input)
            val history = ArrayList(ctx.history).apply {
                if (isEmpty()) add(ctx.systemPrompt.toSystemPromptMessage())
                add(usrMsg)
            }
            ctx.map(history = history) { ctx.input }
        }

    /**
     * Converts LLM's [LLMResponse.Chat.Ok] into text suitable for the user to see.
     *
     * Modifies [AgentContext.input] by replacing the response with the final message content.
     */
    fun responseToString(
        name: String = "Response -> String"
    ): Node<LLMResponse.Chat.Ok, String> = Node(name) { ctx ->
        val content = ctx.input.choices
            .asReversed()
            .firstOrNull { it.message.content.isNotBlank() }
            ?.message
            ?.content
            ?: ctx.input.choices.lastOrNull()?.message?.content
            ?: run {
                l.warn(
                    "LLM returned no choices; using empty response. model={}, created={}",
                    ctx.input.model,
                    ctx.input.created
                )
                ""
            }
        ctx.map { content }
    }

    /**
     * Executes all the [LLMResponse.FunctionCall] from history synchronously.
     *
     * Updates [AgentContext.history] and [AgentContext.input] with tool call results.
     */
    fun toolUse(name: String = "toolUse"): Node<LLMResponse.Chat.Ok, String> = Node(name) { ctx ->
        val fnCallMessages = fnCallMessages(ctx)
        val history = ArrayList(ctx.history).apply { addAll(fnCallMessages) }
        ctx.map(history = history) { ctx.history.last().content }
    }

    /**
     * Makes sure we have Additional Data (AD) in the [AgentContext.history]. Implementation details:
     * - Swap the previous AD with the current one (so agent does have only the current AD, no previous ones);
     * - Append AD before the previous message (so agent is not focused on the AD).
     *
     * Modifies [AgentContext.history] when new data is added.
     */
    fun nodeAppendAdditionalData(name: String = "appendActualInformation"): Node<String, String> = Node(name) { ctx ->
        val additionalMessage: LLMRequest.Message? = appendActualInformation(
            userText = ctx.input,
        )

        val newHistory = ArrayList<LLMRequest.Message>()
        ctx.history.forEach { msg ->
            val isOldContext = msg.role == LLMMessageRole.user && msg.content.contains("<context>")
            if (!isOldContext) newHistory.add(msg)
        }

        if (additionalMessage == null) {
            ctx.map(history = newHistory)
        } else {
            l.info("Injecting additional context (${additionalMessage.content.length} chars)")

            if (newHistory.isNotEmpty()) {
                newHistory.add(newHistory.size - 1, additionalMessage)
            } else {
                newHistory.add(additionalMessage)
            }

            ctx.map(history = newHistory)
        }
    }

    private suspend fun appendActualInformation(
        userText: String,
    ): LLMRequest.Message? {
        if (userText.isBlank()) return null

        val additionalData = ArrayList<StorredData>()

        try {
            additionalData.addAll(desktopInfoRepository.search(userText))
        } catch (e: Exception) {
            l.error("Error searching desktop info: ${e.message}")
        }

        defaultBrowserProvider.defaultBrowserDisplayName()?.let { browserName ->
            additionalData.add(StorredData(browserName, StorredType.DEFAULT_BROWSER))
        }

        val defaultCalendar = settingsProvider.defaultCalendar
        if (!defaultCalendar.isNullOrBlank()) {
            additionalData.add(StorredData("Календарь по умолчанию: $defaultCalendar", StorredType.GENERAL_FACT))
        }

        buildUserGeoLocationFact()?.let { geoFact ->
            additionalData.add(StorredData(geoFact, StorredType.GENERAL_FACT))
        }

        val currentDateTime = ZonedDateTime.now(runtimeEnvironment.zoneId).format(
            DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm:ss", runtimeEnvironment.locale)
        )
        additionalData.add(StorredData("Текущие дата и время: $currentDateTime", StorredType.GENERAL_FACT))

        if (additionalData.isEmpty()) return null

        val content = buildString {
            append("<context>\n")
            append("Background information. Use ONLY if strictly relevant to the user query. If irrelevant (e.g. chitchat), IGNORE completely. Do NOT reference this data in output.\n")
            append("---\n")

            additionalData.forEach { data ->
                val typeReadable = data.type.toString().replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                append("- [$typeReadable]: ${data.text}\n")
            }
            append("</context>")
        }

        return LLMRequest.Message(
            role = LLMMessageRole.user,
            content = content
        )
    }

    private fun buildUserGeoLocationFact(): String? = try {
        val locale = runtimeEnvironment.locale
        val zoneId = runtimeEnvironment.zoneId

        val parts = mutableListOf<String>()

        val localeTag = locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" }
        if (localeTag != null) {
            parts += "locale=$localeTag"
        }

        val countryCode = locale.country.takeIf { it.isNotBlank() }
        if (countryCode != null) {
            val countryName = runCatching { locale.getDisplayCountry(locale) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            val countryValue = if (countryName != null && !countryName.equals(countryCode, ignoreCase = true)) {
                "$countryName ($countryCode)"
            } else {
                countryCode
            }
            parts += "country/region=$countryValue"
        }

        val zoneIdText = zoneId.id.takeIf { it.isNotBlank() }
        if (zoneIdText != null) {
            parts += "timezone=$zoneIdText"
            val cityHint = zoneIdText.substringAfterLast('/', "").replace('_', ' ').trim()
            if (cityHint.isNotBlank() && !cityHint.equals(zoneIdText, ignoreCase = true)) {
                parts += "city_hint=$cityHint"
            }
            parts += "utc_offset=${ZonedDateTime.now(zoneId).offset.id}"
        }

        if (parts.isEmpty()) null else "User geo: ${parts.joinToString("; ")}"
    } catch (e: Exception) {
        l.warn("Error collecting geo location hints: {}", e.message)
        null
    }

    private suspend fun fnCallMessages(ctx: AgentContext<LLMResponse.Chat.Ok>): List<LLMRequest.Message> {
        val fnCallMessages = ctx.input.choices.mapNotNull { choice ->
            val msg = choice.message
            val functionCall = msg.functionCall
            val functionsStateId = msg.functionsStateId
            if (functionCall != null && functionsStateId != null) {
                executeTool(
                    settings = ctx.settings,
                    functionCall = functionCall,
                    meta = ctx.toolInvocationMeta,
                    toolCallId = functionsStateId,
                    eventSink = ctx.runtimeEventSink,
                ).copy(functionsStateId = functionsStateId)
            } else null
        }
        return fnCallMessages
    }

    private suspend fun executeTool(
        settings: AgentSettings,
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
        toolCallId: String? = null,
        eventSink: ru.souz.agent.runtime.AgentRuntimeEventSink = ru.souz.agent.runtime.AgentRuntimeEventSink.NONE,
    ): LLMRequest.Message = agentToolExecutor.execute(
        settings = settings,
        functionCall = functionCall,
        meta = meta,
        toolCallId = toolCallId,
        eventSink = eventSink,
    )
}

internal fun <T> AgentContext<T>.toGigaRequest(history: List<LLMRequest.Message>): LLMRequest.Chat {
    val ctx = this
    return LLMRequest.Chat(
        model = ctx.settings.model,
        messages = history,
        functions = ctx.activeTools,
        temperature = ctx.settings.temperature,
        maxTokens = ctx.settings.contextSize,
    )
}
