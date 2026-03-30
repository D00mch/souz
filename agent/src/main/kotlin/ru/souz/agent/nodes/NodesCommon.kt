package ru.souz.agent.nodes

import org.slf4j.LoggerFactory
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.AgentSettings
import ru.souz.agent.engine.Node
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.db.StorredData
import ru.souz.db.StorredType
import ru.souz.llms.GigaMessageRole
import ru.souz.llms.GigaModel
import ru.souz.llms.GigaRequest
import ru.souz.llms.GigaResponse
import ru.souz.llms.LlmProvider
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
class NodesCommon(
    private val desktopInfoRepository: AgentDesktopInfoRepository,
    private val settingsProvider: AgentSettingsProvider,
    private val agentToolExecutor: AgentToolExecutor,
    private val defaultBrowserProvider: DefaultBrowserProvider,
) {
    private val l = LoggerFactory.getLogger(NodesCommon::class.java)

    /**
     * Ensures proper history with user input as a message.
     *
     * Modifies [AgentContext.history] while preserving [AgentContext.input].
     */
    fun inputToHistory(name: String = "Input->History"): Node<String, String> =
        Node(name) { ctx ->
            val usrMsg = GigaRequest.Message(GigaMessageRole.user, ctx.input)
            val history = ArrayList(ctx.history).apply {
                if (isEmpty()) add(ctx.systemPrompt.toSystemPromptMessage())
                add(usrMsg)
            }
            ctx.map(history = history) { ctx.input }
        }

    /**
     * Converts LLM's [GigaResponse.Chat.Ok] into text suitable for the user to see.
     *
     * Modifies [AgentContext.input] by replacing the response with the final message content.
     */
    fun responseToString(
        name: String = "Response -> String"
    ): Node<GigaResponse.Chat.Ok, String> = Node(name) { ctx ->
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
     * Executes all the [GigaResponse.FunctionCall] from history synchronously.
     *
     * Updates [AgentContext.history] and [AgentContext.input] with tool call results.
     */
    fun toolUse(name: String = "toolUse"): Node<GigaResponse.Chat.Ok, String> = Node(name) { ctx ->
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
        val additionalMessage: GigaRequest.Message? = appendActualInformation(
            userText = ctx.input,
            modelAlias = ctx.settings.model,
        )

        val newHistory = ArrayList<GigaRequest.Message>()
        ctx.history.forEach { msg ->
            val isOldContext = msg.role == GigaMessageRole.user && msg.content.contains("<context>")
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
        modelAlias: String,
    ): GigaRequest.Message? {
        if (userText.isBlank()) return null

        val additionalData = ArrayList<StorredData>()

        if (!isLocalModelAlias(modelAlias)) {
            try {
                additionalData.addAll(desktopInfoRepository.search(userText))
            } catch (e: Exception) {
                l.error("Error searching desktop info: ${e.message}")
            }
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

        val currentDateTime = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm:ss")
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

        return GigaRequest.Message(
            role = GigaMessageRole.user,
            content = content
        )
    }

    private fun buildUserGeoLocationFact(): String? = try {
        val locale = Locale.getDefault()
        val zoneId = ZoneId.systemDefault()

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

    private suspend fun fnCallMessages(ctx: AgentContext<GigaResponse.Chat.Ok>): List<GigaRequest.Message> {
        val fnCallMessages = ctx.input.choices.mapNotNull { choice ->
            val msg = choice.message
            if (msg.functionCall != null && msg.functionsStateId != null) {
                executeTool(ctx.settings, msg.functionCall).copy(functionsStateId = msg.functionsStateId)
            } else null
        }
        return fnCallMessages
    }

    private suspend fun executeTool(
        settings: AgentSettings,
        functionCall: GigaResponse.FunctionCall,
    ): GigaRequest.Message = agentToolExecutor.execute(settings, functionCall)

    private fun isLocalModelAlias(modelAlias: String): Boolean =
        GigaModel.entries.any { model ->
            model.alias.equals(modelAlias, ignoreCase = true) && model.provider == LlmProvider.LOCAL
        }
}

fun <T> AgentContext<T>.toGigaRequest(history: List<GigaRequest.Message>): GigaRequest.Chat {
    val ctx = this
    return GigaRequest.Chat(
        model = ctx.settings.model,
        messages = history,
        functions = ctx.activeTools,
        temperature = ctx.settings.temperature,
        maxTokens = ctx.settings.contextSize,
    )
}
