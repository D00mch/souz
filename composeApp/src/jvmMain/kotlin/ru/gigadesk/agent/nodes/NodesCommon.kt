package ru.gigadesk.agent.node

import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.agent.engine.AgentSettings
import ru.gigadesk.agent.engine.Node
import ru.gigadesk.giga.GigaMessageRole
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.GigaToolSetup
import ru.gigadesk.giga.toSystemPromptMessage
import org.slf4j.LoggerFactory
import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.db.asString
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.browser.detectDefaultBrowser
import ru.gigadesk.tool.browser.prettyName
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class NodesCommon(
    private val desktopInfoRepository: DesktopInfoRepository,
    private val settingsProvider: SettingsProvider,
) {
    private val l = LoggerFactory.getLogger(NodesCommon::class.java)

    val stringToReq: Node<String, GigaRequest.Chat> = Node("String->Request") { ctx ->
        val usrMsg = GigaRequest.Message(GigaMessageRole.user, ctx.input)
        val history = ArrayList(ctx.history).apply {
            if (isEmpty()) add(ctx.systemPrompt.toSystemPromptMessage())
            add(usrMsg)
        }
        ctx.map(history = history) { ctx.toGigaRequest(history) }
    }

    val respToString: Node<GigaResponse.Chat, String> = Node("Response->String") { ctx ->
        when (val input = ctx.input) {
            is GigaResponse.Chat.Error -> ctx.map { input.message }
            is GigaResponse.Chat.Ok -> ctx.map { input.choices.last().message.content }
        }
    }

    val toolUse: Node<GigaResponse.Chat, GigaRequest.Chat> = Node("toolUse") { ctx ->
        val fnCallMessages = fnCallMessages(ctx)
        val history = ArrayList(ctx.history).apply { addAll(fnCallMessages) }
        ctx.map(history = history) { ctx.toGigaRequest(history) }
    }

    /**
     * Makes sure we have additional information (AD) in the history, 2 cases possible:
     * - Swap the previous AD with the current one;
     * - Append AD before the previous message (so agent is not focused on the AD).
     */
    val nodeAppendAdditionalData: Node<String, String> = Node("appendActualInformation") { ctx ->
        val additionalMessage: GigaRequest.Message? = appendActualInformation(ctx.input)
        if (additionalMessage == null) {
            ctx
        } else {
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

        val msgRelatedDataInTheStore: String = try {
            desktopInfoRepository.search(userText).asString()
        } catch (e: Exception) {
            l.error("Error while searching desktop info: {}", e.message)
            ""
        }

        val browserName = try {
            val defaultBrowser = ToolRunBashCommand.detectDefaultBrowser()
            "Дефолтный браузер — ${defaultBrowser.prettyName}"
        } catch (e: Exception) {
            l.error("Error while fetching opened tabs: {}", e.message)
            ""
        }

        val defaultCalendarName = settingsProvider.defaultCalendar
        val calendarInfo = if (!defaultCalendarName.isNullOrBlank()) {
            "Default calendar: $defaultCalendarName"
        } else {
            ""
        }

        val currentDateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val dateInfo = "Текущие дата и время: $currentDateTime"

        return GigaRequest.Message(
            role = GigaMessageRole.user,
            content = listOf(
                INFO_PREFIX,
                msgRelatedDataInTheStore,
                browserName,
                calendarInfo,
                dateInfo
            )
                .filter { it.isNotBlank() }
                .joinToString(separator = ";\n")
        )
    }

    private suspend fun fnCallMessages(ctx: AgentContext<GigaResponse.Chat>): List<GigaRequest.Message> {
        val fnCallMessages = (ctx.input as GigaResponse.Chat.Ok).choices.mapNotNull { choice ->
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

private const val INFO_PREFIX = "Используя данные ниже, помоги с вопросом. Данные \n\n"