package ru.souz.agent.nodes

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Node
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
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
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.ConversationId
import ru.souz.memory.MemoryContext
import ru.souz.memory.MemoryOwnerId

import ru.souz.memory.MemoryRetrievalRequest
import ru.souz.memory.MemorySessionId
import ru.souz.memory.NoopConversationMemoryRuntime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val INJECTED_CONTEXT_PREFIX = "<context>\nBackground information. Use ONLY if strictly relevant to the user query. If irrelevant (e.g. chitchat), IGNORE completely. Do NOT reference this data in output.\n---\n"
private const val INJECTED_CONTEXT_SUFFIX = "</context>"

internal fun LLMRequest.Message.isInjectedContextMessage(): Boolean =
    role == LLMMessageRole.user &&
        content.startsWith(INJECTED_CONTEXT_PREFIX) &&
        content.endsWith(INJECTED_CONTEXT_SUFFIX)

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
    private val memoryRuntime: ConversationMemoryRuntime = NoopConversationMemoryRuntime,
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
        val additionalMessage = appendActualInformation(
            userText = ctx.input,
            meta = ctx.toolInvocationMeta,
            eventSink = ctx.runtimeEventSink,
        )

        val newHistory = ctx.history
            .filterNot(LLMRequest.Message::isInjectedContextMessage)
            .toMutableList()
        additionalMessage?.let {
            l.info("Injecting additional context ({} chars)", it.content.length)
            if (newHistory.isEmpty()) newHistory += it else newHistory.add(newHistory.lastIndex, it)
        }
        ctx.map(history = newHistory)
    }

    private suspend fun appendActualInformation(
        userText: String,
        meta: ToolInvocationMeta,
        eventSink: AgentRuntimeEventSink,
    ): LLMRequest.Message? {
        if (userText.isBlank()) return null

        val memoryBlock = retrieveMemoryBlock(userText, meta, eventSink)
        val additionalData = loadAdditionalData(userText)
        if (memoryBlock == null && additionalData.isEmpty()) return null
        return LLMRequest.Message(LLMMessageRole.user, buildContextMessage(memoryBlock, additionalData))
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

    private suspend fun fnCallMessages(ctx: AgentContext<LLMResponse.Chat.Ok>): List<LLMRequest.Message> =
        ctx.input.choices.mapNotNull { choice ->
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

    private suspend fun executeTool(
        settings: AgentSettings,
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
        toolCallId: String? = null,
        eventSink: AgentRuntimeEventSink = AgentRuntimeEventSink.NONE,
    ): LLMRequest.Message = agentToolExecutor.execute(
        settings = settings,
        functionCall = functionCall,
        meta = meta,
        toolCallId = toolCallId,
        eventSink = eventSink,
    )

    private suspend fun retrieveMemoryBlock(
        userText: String,
        meta: ToolInvocationMeta,
        eventSink: AgentRuntimeEventSink,
    ): String? {
        val memoryResult = try {
            memoryRuntime.retrieveMemory(
                MemoryRetrievalRequest(
                    context = MemoryContext(
                        ownerId = MemoryOwnerId(meta.userId),
                        conversationId = meta.conversationId?.let(::ConversationId),
                        sessionId = meta.conversationId?.let(::MemorySessionId),
                        projectId = null,
                    ),
                    query = userText,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            l.warn("Memory retrieval failed: {}", e.message)
            return null
        }
        val renderedBlock = memoryResult.renderedPromptBlock.orEmpty().trim()
        if (renderedBlock.isBlank()) return null
        try {
            eventSink.emit(AgentRuntimeEvent.MemoryPromptAugmented(renderedBlock, memoryResult.facts))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            l.warn("Memory augmentation trace failed: {}", e.message)
        }
        return renderedBlock
    }

    private suspend fun loadAdditionalData(userText: String): List<StorredData> = buildList {
        try {
            addAll(desktopInfoRepository.search(userText))
        } catch (e: Exception) {
            l.error("Error searching desktop info: ${e.message}")
        }
        defaultBrowserProvider.defaultBrowserDisplayName()?.let {
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

    private fun buildContextMessage(
        memoryBlock: String?,
        additionalData: List<StorredData>,
    ): String = buildString {
        append(INJECTED_CONTEXT_PREFIX)
        memoryBlock?.let {
            append(it)
            append('\n')
        }
        if (additionalData.isNotEmpty()) {
            if (memoryBlock != null) append("\nOther relevant context:\n")
            additionalData.forEach { append("- [${it.readableType()}]: ${it.text}\n") }
        }
        append(INJECTED_CONTEXT_SUFFIX)
    }

    private fun StorredData.readableType(): String =
        type.toString().replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
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
