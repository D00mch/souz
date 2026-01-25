package ru.gigadesk.agent.nodes

import org.slf4j.LoggerFactory
import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.agent.engine.AgentSettings
import ru.gigadesk.agent.engine.Node
import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.db.StorredData
import ru.gigadesk.db.StorredType
import ru.gigadesk.giga.*
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.browser.detectDefaultBrowser
import ru.gigadesk.tool.browser.prettyName
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
        ctx.map { ctx.input.choices.last().message.content }
    }

    /**
     * Executes all the [GigaResponse.FunctionCall] from history synchronously.
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
        if (additionalMessage == null) {
            ctx
        } else {
            l.info("Additional data:\n$additionalMessage")
            val newHistory = ArrayList<GigaRequest.Message>()
            ctx.history.forEach { msg ->
                val isAdditionalData = msg.role == GigaMessageRole.user && msg.content.startsWith(INFO_PREFIX)
                if (!isAdditionalData) newHistory.add(msg)
            }

            if (ctx.history.size > 1) {
                newHistory.apply { add(size - 1, additionalMessage) }
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
            l.error("Error while searching desktop info: {}", e.message)
        }

        try {
            val defaultBrowser = ToolRunBashCommand.detectDefaultBrowser().prettyName
            additionalData.add(StorredData("Дефолтный браузер — $defaultBrowser", StorredType.DEFAULT_BROWSER))
        } catch (e: Exception) {
            l.error("Error while fetching opened tabs: {}", e.message)
        }

        val defaultCalendarName = settingsProvider.defaultCalendar
        if (!defaultCalendarName.isNullOrBlank()) {
            additionalData.add(StorredData("Default calendar: $defaultCalendarName", StorredType.GENERAL_FACT))
        }

        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).let { currentDateTime ->
            val dateInfo = "Текущие дата и время: $currentDateTime"
            additionalData.add(StorredData(dateInfo, StorredType.GENERAL_FACT))
        }

        return GigaRequest.Message(
            role = GigaMessageRole.user,
            content = additionalData.joinToString(prefix = "$INFO_PREFIX:\n", separator = "\n") { data ->
                "${data.type}. ${data.text}"
            }
        )
    }

    private suspend fun fnCallMessages(ctx: AgentContext<GigaResponse.Chat.Ok>): List<GigaRequest.Message> {
        val fnCallMessages = ctx.input.choices.mapNotNull { choice ->
            val msg = choice.message
            if (msg.functionCall != null && msg.functionsStateId != null) {
                executeTool(ctx.settings, msg.functionCall)
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
    )
}

private const val INFO_PREFIX = "Данные, которые могут оказаться полезными"
