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
        val newHistory = ctx.history
            .filterIndexed { index, message ->
                index == currentUserIndex || !message.isInjectedContextMessage()
            }
            .toMutableList()
        additionalMessage?.let {
            l.info("Injecting additional context ({} chars)", it.content.length)
            if (newHistory.isEmpty()) newHistory += it else newHistory.add(newHistory.lastIndex, it)
        }
        ctx.map(history = newHistory)
    }

    private suspend fun appendActualInformation(
        userText: String,
    ): LLMRequest.Message? {
        if (userText.isBlank()) return null

        val additionalData = loadAdditionalData(userText)
        if (additionalData.isEmpty()) return null
        return LLMRequest.Message(LLMMessageRole.user, buildContextMessage(additionalData))
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

    private suspend fun loadAdditionalData(userText: String): List<StorredData> = buildList {
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
        buildUserGeoLocationFact()?.let { add(StorredData(it, StorredType.GENERAL_FACT)) }
        add(
            StorredData(
                "Текущие дата и время: ${
                    ZonedDateTime.now(runtimeEnvironment.zoneId).format(
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

internal fun <T> AgentContext<T>.toGigaRequest(history: List<LLMRequest.Message>): LLMRequest.Chat {
    val ctx = this
    return LLMRequest.Chat(
        model = ctx.settings.model,
        provider = ctx.settings.provider,
        messages = history,
        functions = ctx.activeTools,
        temperature = ctx.settings.temperature,
        maxTokens = ctx.settings.contextSize,
    )
}
