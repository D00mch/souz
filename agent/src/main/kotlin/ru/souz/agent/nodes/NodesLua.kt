package ru.souz.agent.nodes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import ru.souz.agent.graph.Node
import ru.souz.agent.state.AgentContext
import ru.souz.agent.runtime.LuaExecutionException
import ru.souz.agent.runtime.LuaRuntime
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.toMessage

// TODO: review after agent
internal class NodesLua(
    private val llmApi: LLMChatAPI,
    private val luaRuntime: LuaRuntime,
) {
    sealed interface LuaExecutionResult {
        data class Success(val output: String) : LuaExecutionResult
        data class Failure(
            val brokenCode: String,
            val error: LuaExecutionException,
        ) : LuaExecutionResult
    }

    val sideEffects: Flow<String> = emptyFlow()

    private val systemPrompt: String =
        LUA_AGENT_SYSTEM_PROMPT.format(LuaRuntime.forbidden.joinToString(", ") { "`$it`" })

    fun plan(name: String = "Lua LLM"): Node<String, LLMResponse.Chat> = Node(name) { ctx ->
        val response = withContext(Dispatchers.IO) {
            llmApi.message(buildRequest(ctx))
        }
        val history = when (response) {
            is LLMResponse.Chat.Error -> ctx.history
            is LLMResponse.Chat.Ok -> ArrayList(ctx.history).apply {
                addAll(response.choices.mapNotNull { it.toMessage() })
            }
        }
        ctx.map(history = history) { response }
    }

    fun responseToCode(name: String = "Lua -> Code"): Node<String, String> = Node(name) { ctx ->
        ctx.map { extractLuaCode(ctx.input) }
    }

    fun execute(name: String = "Lua Runtime"): Node<String, LuaExecutionResult> = Node(name) { ctx ->
        try {
            val result = luaRuntime.execute(
                code = ctx.input,
                settings = ctx.settings,
                activeTools = ctx.activeTools,
                eventSink = ctx.runtimeEventSink,
            )
            val history = ArrayList(ctx.history).apply {
                add(
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = result,
                    )
                )
            }
            ctx.map(history = history) { LuaExecutionResult.Success(result) }
        } catch (e: LuaExecutionException) {
            ctx.map { LuaExecutionResult.Failure(brokenCode = ctx.input, error = e) }
        }
    }

    fun executeSuccessToString(name: String = "Lua Runtime.Ok"): Node<LuaExecutionResult, String> = Node(name) { ctx ->
        val success = ctx.input as LuaExecutionResult.Success
        ctx.map { success.output }
    }

    fun executeFailureToRepair(name: String = "Lua Runtime.Error"): Node<LuaExecutionResult, LuaExecutionResult.Failure> =
        Node(name) { ctx ->
            ctx.map { ctx.input as LuaExecutionResult.Failure }
        }

    fun repair(
        name: String = "Lua Repair",
    ): Node<LuaExecutionResult.Failure, LLMResponse.Chat> = Node(name) { ctx ->
        val latestUserRequest = ctx.history
            .lastOrNull { it.role == LLMMessageRole.user && !it.content.contains("<context>") }
            ?.content
            .orEmpty()

        val response = withContext(Dispatchers.IO) {
            llmApi.message(
                LLMRequest.Chat(
                    model = ctx.settings.model,
                    messages = listOf(
                        LLMRequest.Message(
                            role = LLMMessageRole.system,
                            content = buildRepairSystemPrompt(
                                basePrompt = ctx.systemPrompt,
                                tools = ctx.activeTools,
                            ),
                        ),
                        LLMRequest.Message(
                            role = LLMMessageRole.user,
                            content = buildString {
                                appendLine("Original user request:")
                                appendLine(latestUserRequest)
                                appendLine()
                                appendLine("Lua error:")
                                appendLine(ctx.input.error.cause?.message ?: ctx.input.error.message.orEmpty())
                                appendLine()
                                appendLine("Broken Lua code:")
                                appendLine("```lua")
                                appendLine(ctx.input.brokenCode)
                                appendLine("```")
                            }.trim(),
                        ),
                    ),
                    functions = emptyList(),
                    temperature = 0f,
                    maxTokens = ctx.settings.contextSize,
                )
            )
        }

        val history = when (response) {
            is LLMResponse.Chat.Error -> ctx.history
            is LLMResponse.Chat.Ok -> ArrayList(ctx.history).apply {
                addAll(response.choices.mapNotNull { it.toMessage() })
            }
        }
        ctx.map(history = history) { response }
    }

    private fun buildRequest(ctx: AgentContext<String>): LLMRequest.Chat {
        val conversation = ctx.history.filterNot { it.role == LLMMessageRole.system }
        val messages = listOf(
            LLMRequest.Message(
                role = LLMMessageRole.system,
                content = buildSystemPrompt(
                    basePrompt = ctx.systemPrompt,
                    tools = ctx.activeTools,
                ),
            )
        ) + conversation

        return LLMRequest.Chat(
            model = ctx.settings.model,
            messages = messages,
            functions = emptyList(),
            temperature = ctx.settings.temperature,
            maxTokens = ctx.settings.contextSize,
        )
    }

    private fun buildSystemPrompt(
        basePrompt: String,
        tools: List<LLMRequest.Function>,
    ): String = buildString {
        appendLine(systemPrompt)
        appendLine()
        appendLine("Additional instructions from the app/user:")
        appendLine(basePrompt.ifBlank { "No extra instructions." })
        appendLine()
        appendLine("Available tools:")
        appendLine(buildToolCatalog(tools))
    }.trim()

    private fun buildToolCatalog(tools: List<LLMRequest.Function>): String {
        if (tools.isEmpty()) return "- No tools available for this turn."

        return tools.joinToString(separator = "\n\n") { function ->
            buildString {
                append("- ")
                append(function.name)
                append(": ")
                append(function.description)

                if (function.parameters.properties.isEmpty()) {
                    append("\n  Parameters: none")
                } else {
                    append("\n  Parameters:")
                    function.parameters.properties.forEach { (name, property) ->
                        appendProperties(name, property, function.parameters.required)
                    }
                }

                append("\n  Returns:")
                val returnParameters = function.returnParameters
                returnParameters?.properties?.forEach { (name, property) ->
                    appendProperties(name, property, returnParameters.required)
                }
                /*
                    Available tools:
                    - MailReplyMessage: Reply to a specific message by its ID.
                      Parameters:
                      - messageId: number required - The unique ID of the message (required for reply)
                      - content: string - Body content for reply
                      Returns:
                 */
            }
        }
    }

    private fun StringBuilder.appendProperties(
        name: String,
        property: LLMRequest.Property,
        required: List<String>
    ) {
        append("\n  - ")
        append(name)
        append(": ")
        append(property.type)
        if (name in required) append(" required")
        property.description?.takeIf { it.isNotBlank() }?.let {
            append(" - ")
            append(it)
        }
        property.enum?.takeIf { it.isNotEmpty() }?.let {
            append(" (enum: ")
            append(it.joinToString())
            append(")")
        }
    }

    private fun extractLuaCode(content: String): String {
        val codeBlock = LUA_BLOCK_REGEX.find(content)?.groupValues?.get(1)
        return (codeBlock ?: content).trim()
    }

    private fun buildRepairSystemPrompt(
        basePrompt: String,
        tools: List<LLMRequest.Function>,
    ): String = buildString {
        appendLine(systemPrompt)
        appendLine()
        appendLine(LUA_REPAIR_SYSTEM_PROMPT)
        appendLine()
        appendLine("Additional instructions from the app/user:")
        appendLine(basePrompt.ifBlank { "No extra instructions." })
        appendLine()
        appendLine("Available tools:")
        appendLine(buildToolCatalog(tools))
    }.trim()
}

private val LUA_BLOCK_REGEX = Regex("""```(?:lua)?\s*([\s\S]*?)```""", RegexOption.IGNORE_CASE)

private const val LUA_AGENT_SYSTEM_PROMPT = """
You are an autonomous assistant that must solve the task by generating Lua code.
Your response is executed immediately in a Lua runtime. Follow these rules strictly:
1. Reply with exactly one fenced ```lua``` block and no extra prose.
2. Use only the provided runtime APIs: `ToolName({...})`, `tools["ToolName"]({...})`, or `call_tool("ToolName", {...})`.
3. Tool calls return host-decoded Lua values. If a returned string itself contains JSON text, call `json_decode(...)` manually.
4. End the script with `return "final markdown for the user"`. The returned string is shown to the user verbatim.
5. Never paste raw multiline text inside quoted string literals. Keep tool output in variables and pass variables directly. If you need a multiline literal, use Lua long brackets `[[...]]`.
6. Do not use %s, or invented helpers.
7. If no tool is needed, still return Lua code that directly returns the answer.
"""

private const val LUA_REPAIR_SYSTEM_PROMPT = """
You are repairing broken Lua code generated by another assistant step.
Return corrected Lua only.
Keep the same intent.
Fix syntax/runtime issues conservatively.
For multiline text values, prefer reusing variables or Lua long bracket strings.
"""
