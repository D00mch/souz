package ru.souz.agent.nodes

import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Node
import ru.souz.agent.state.AgentContext
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.db.StorredData
import ru.souz.db.StorredType
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val INJECTED_CONTEXT_PREFIX = "<context>\nBackground information. Use ONLY if strictly relevant to the user query. If irrelevant (e.g. chitchat), IGNORE completely. Do NOT reference this data in output.\n---\n"
private const val INJECTED_CONTEXT_SUFFIX = "</context>"

internal fun LLMRequest.Message.isInjectedContextMessage(): Boolean =
    role == LLMMessageRole.user &&
        content.startsWith(INJECTED_CONTEXT_PREFIX) &&
        content.endsWith(INJECTED_CONTEXT_SUFFIX)

/** Enriches conversation history with host-provided context. */
internal class NodesCommon(
    private val desktopInfoRepository: AgentDesktopInfoRepository,
    private val settingsProvider: AgentSettingsProvider,
    private val runtimeEnvironment: AgentRuntimeEnvironment,
) {
    private val l = LoggerFactory.getLogger(NodesCommon::class.java)

    /**
     * Makes sure we have Additional Data (AD) in the [AgentContext.history]. Implementation details:
     * - Swap the previous AD with the current one (so agent does have only the current AD, no previous ones);
     * - Append AD before the previous message (so agent is not focused on the AD).
     *
     * Modifies [AgentContext.history] when new data is added.
     */
    fun nodeAppendAdditionalData(name: String = "appendActualInformation"): Node<String, String> = Node(name) { ctx ->
        val additionalMessage = appendActualInformation(ctx.input)

        val currentUserIndex = ctx.history.indexOfLast { it.role == LLMMessageRole.user }
        var activeExchangeStartIndex = ctx.activeExchangeStartIndex
        val newHistory = ctx.history
            .filterIndexed { index, message ->
                val keep = index == currentUserIndex || !message.isInjectedContextMessage()
                if (!keep && index < ctx.activeExchangeStartIndex) activeExchangeStartIndex--
                keep
            }
            .toMutableList()
        additionalMessage?.let {
            l.info("Injecting additional context ({} chars)", it.content.length)
            newHistory.add(maxOf(activeExchangeStartIndex, newHistory.lastIndex), it)
        }
        ctx.map(history = newHistory, activeExchangeStartIndex = activeExchangeStartIndex)
    }

    private suspend fun appendActualInformation(
        userText: String,
    ): LLMRequest.Message? {
        if (userText.isBlank()) return null

        val additionalData = loadAdditionalData(userText)
        if (additionalData.isEmpty()) return null
        return LLMRequest.Message(LLMMessageRole.user, buildContextMessage(additionalData))
    }

    private fun buildUserGeoLocationFact(now: ZonedDateTime): String? = try {
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
            parts += "utc_offset=${now.offset.id}"
        }

        if (parts.isEmpty()) null else "User geo: ${parts.joinToString("; ")}"
    } catch (e: Exception) {
        l.warn("Error collecting geo location hints: {}", e.message)
        null
    }

    private suspend fun loadAdditionalData(userText: String): List<StorredData> = buildList {
        val now = runtimeEnvironment.clock.instant().atZone(runtimeEnvironment.zoneId)
        try {
            addAll(desktopInfoRepository.search(userText))
        } catch (e: Exception) {
            l.error("Error searching desktop info: ${e.message}")
        }
        runtimeEnvironment.defaultBrowserDisplayName?.let {
            add(StorredData(it, StorredType.DEFAULT_BROWSER))
        }
        settingsProvider.defaultCalendar
            ?.takeIf(String::isNotBlank)
            ?.let { add(StorredData("Календарь по умолчанию: $it", StorredType.GENERAL_FACT)) }
        buildUserGeoLocationFact(now)?.let { add(StorredData(it, StorredType.GENERAL_FACT)) }
        add(
            StorredData(
                "Текущие дата и время: ${
                    now.format(
                        DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm:ss", runtimeEnvironment.locale)
                    )
                }",
                StorredType.GENERAL_FACT,
            )
        )
    }

    private fun buildContextMessage(additionalData: List<StorredData>): String = buildString {
        append(INJECTED_CONTEXT_PREFIX)
        additionalData.forEach { append("- [${it.readableType()}]: ${it.text}\n") }
        append(INJECTED_CONTEXT_SUFFIX)
    }

    private fun StorredData.readableType(): String =
        type.toString().replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
}

internal fun AgentContext<*>.toChatRequest(
    history: List<LLMRequest.Message>,
    maxOutputTokens: Int,
): LLMRequest.Chat = LLMRequest.Chat(
    model = settings.model,
    provider = settings.provider,
    messages = history,
    functions = activeTools,
    temperature = settings.temperature,
    maxTokens = maxOutputTokens,
    contextSize = settings.contextSize,
    reasoningEffort = settings.reasoningEffort,
)

/** Providers receive message text only: list each non-tool message's files so the model can use their IDs. */
internal fun LLMRequest.Chat.withFileReferences(): LLMRequest.Chat = copy(messages = messages.map { it.withFileReferences() })

internal fun LLMRequest.Message.withFileReferences(): LLMRequest.Message {
    // Tool results already carry their file metadata in the JSON content.
    if (files.isEmpty() || role == LLMMessageRole.function) return this
    val references = files.joinToString("\n") {
        "- ${it.name} (fileId: ${it.fileId}, ${it.mimeType}, ${it.sizeBytes} bytes, ${it.presentation})"
    }
    return copy(content = "$content\n\n$FILE_REFERENCES_HEADER\n$references".trimStart())
}

private const val FILE_REFERENCES_HEADER = "[Files available by fileId]"

/** Merge ephemeral host instructions without changing the stored conversation. */
internal fun LLMRequest.Chat.withConversationInstructions(instructions: String): LLMRequest.Chat {
    if (instructions.isEmpty()) return this
    val system = messages.indexOfFirst { it.role == LLMMessageRole.system }
    val prepared = messages.toMutableList()
    if (system < 0) prepared.add(0, LLMRequest.Message(LLMMessageRole.system, instructions))
    else prepared[system] = prepared[system].copy(content = prepared[system].content + "\n\n" + instructions)
    return copy(messages = prepared)
}
