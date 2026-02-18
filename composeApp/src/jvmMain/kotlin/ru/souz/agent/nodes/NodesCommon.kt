package ru.souz.agent.nodes

import org.slf4j.LoggerFactory
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.AgentSettings
import ru.souz.agent.engine.Node
import ru.souz.db.DesktopInfoRepository
import ru.souz.db.SettingsProvider
import ru.souz.db.StorredData
import ru.souz.db.StorredType
import ru.souz.giga.*
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.browser.detectDefaultBrowser
import ru.souz.tool.browser.prettyName
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Nodes related to local data manipulation.
 * The nodes may update [AgentContext.input] or [AgentContext.history].
 */
class NodesCommon(
    private val desktopInfoRepository: DesktopInfoRepository,
    private val settingsProvider: SettingsProvider,
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
        val additionalMessage: GigaRequest.Message? = appendActualInformation(ctx.input)

        val newHistory = ArrayList<GigaRequest.Message>()
        ctx.history.forEach { msg ->
            val isOldContext = msg.role == GigaMessageRole.user && msg.content.contains("<context>")
            if (!isOldContext) newHistory.add(msg)
        }

        if (additionalMessage == null) {
            ctx.map(history = newHistory)
        } else {
            l.info("Injecting RAG context (${additionalMessage.content.length} chars)")

            if (newHistory.isNotEmpty()) {
                newHistory.add(newHistory.size - 1, additionalMessage)
            } else {
                newHistory.add(additionalMessage)
            }

            ctx.map(history = newHistory)
        }
    }

    private suspend fun appendActualInformation(userText: String): GigaRequest.Message? {
        if (userText.isBlank()) return null

        val additionalData = ArrayList<StorredData>()

        try {
            additionalData.addAll(desktopInfoRepository.search(userText))
        } catch (e: Exception) {
            l.error("Error searching desktop info: ${e.message}")
        }

        try {
            val defaultBrowser = ToolRunBashCommand.detectDefaultBrowser().prettyName
            additionalData.add(StorredData(defaultBrowser, StorredType.DEFAULT_BROWSER))
        } catch (e: Exception) {
            l.error("Error fetching browser info: ${e.message}")
        }

        val defaultCalendar = settingsProvider.defaultCalendar
        if (!defaultCalendar.isNullOrBlank()) {
            additionalData.add(StorredData(defaultCalendar, StorredType.GENERAL_FACT))
        }

        val currentDateTime = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm:ss")
        )
        additionalData.add(StorredData(currentDateTime, StorredType.GENERAL_FACT))

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
    ): GigaRequest.Message {
        val tools = settings.tools
        val fn: GigaToolSetup = tools[functionCall.name] ?: return GigaRequest.Message(
            GigaMessageRole.function, """{"result":"no such function ${functionCall.name}"}"""
        )
        l.info("Executing tool: ${fn.fn.name}, arguments: ${functionCall.arguments}")
        return fn.invoke(functionCall)
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
