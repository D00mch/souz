package agent

import java.io.File
import kotlinx.coroutines.flow.Flow
import ru.souz.agent.AgentId
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

/** A strict script at the provider boundary; graph execution and memory remain real. */
internal class ScriptedMemoryChatApi(private val agent: AgentId) : LLMChatAPI {
    private data class Step(
        val text: String = "",
        val call: LLMResponse.FunctionCall? = null,
        val classification: Boolean = false,
    )

    private val pending = ArrayDeque<Step>()
    private var userText: String? = null
    private var expectedToolResult: String? = null
    private var failure: String? = null
    val chatRequests = mutableListOf<LLMRequest.Chat>()

    fun beginTurn(turn: MemoryTurn) {
        verifyComplete()
        require(!turn.assistant.isNullOrBlank()) { "Missing scripted assistant answer for ${turn.id}" }
        require(turn.toolCalls.map { it.name }.toSet() == turn.toolResults.keys) {
            "Scripted calls and fixed results must match for ${turn.id}"
        }
        userText = turn.text
        expectedToolResult = null
        chatRequests.clear()
        if (agent == AgentId.GRAPH) pending += Step("HELP 100", classification = true)
        turn.toolCalls.forEach { call ->
            if (agent == AgentId.SKILLS_GRAPH) {
                pending += Step(call = LLMResponse.FunctionCall("GetSkillByName", mapOf("skillId" to call.name)))
                pending += Step(call = LLMResponse.FunctionCall("RunSkillCommand",
                    mapOf("skillId" to call.name, "arguments" to call.arguments)))
            } else {
                pending += Step(call = LLMResponse.FunctionCall(call.name, call.arguments))
            }
        }
        pending += Step(turn.assistant)
    }

    fun beginProbe(probe: MemoryProbe) = beginTurn(MemoryTurn(
        id = probe.id, text = probe.question, assistant = "Проверка передачи контекста завершена.",
    ))

    fun verifyComplete() {
        check(failure == null) { failure.orEmpty() }
        check(pending.isEmpty()) { "Unused scripted responses: ${pending.size}" }
    }

    fun memoryContext(): String = chatRequests.last().messages
        .filter { it.name == "souz_injected_memory" && it.role == LLMMessageRole.user }
        .joinToString("\n") { it.content }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (failure != null) unexpected(failure.orEmpty())
        if (body.isSummarization || body.stream) unexpected("Unexpected summarization or streaming request")
        val step = pending.firstOrNull() ?: unexpected("Unexpected extra LLM request")
        val lastUser = body.messages.lastOrNull { it.role == LLMMessageRole.user }?.content
        if (step.classification) {
            if (lastUser != "New message:\n$userText" || body.functions.isNotEmpty()) {
                unexpected("Expected graph classification request")
            }
        } else {
            if (lastUser != userText) unexpected("Unexpected user message at the LLM boundary")
            expectedToolResult?.let { name ->
                val currentTurn = body.messages.drop(body.messages.indexOfLast { it.role == LLMMessageRole.user } + 1)
                if (currentTurn.lastOrNull { it.role == LLMMessageRole.function }?.name != name) {
                    unexpected("Missing tool result for $name")
                }
            }
            step.call?.let { call ->
                if (body.functions.none { it.name == call.name }) unexpected("Scripted tool is unavailable: ${call.name}")
            }
            chatRequests += body
            expectedToolResult = step.call?.name
        }
        pending.removeFirst()
        return LLMResponse.Chat.Ok(
            choices = listOf(LLMResponse.Choice(
                LLMResponse.Message(step.text, LLMMessageRole.assistant, step.call,
                    functionsStateId = step.call?.let { "script-call-${chatRequests.size}" }),
                0, if (step.call == null) LLMResponse.FinishReason.stop else LLMResponse.FinishReason.function_call,
            )),
            created = 0, model = "scripted", usage = LLMResponse.Usage(0, 0, 0, 0),
        )
    }

    // Remember violations because graph classification may catch provider exceptions and continue.
    private fun unexpected(message: String): Nothing {
        failure = message
        error(message)
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = unexpected("Unexpected streaming")
    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings = unexpected("Unexpected embeddings")
    override suspend fun uploadFile(file: File): LLMResponse.UploadFile = unexpected("Unexpected upload")
    override suspend fun downloadFile(fileId: String): String? = unexpected("Unexpected download")
    override suspend fun balance(): LLMResponse.Balance = unexpected("Unexpected balance request")
}
