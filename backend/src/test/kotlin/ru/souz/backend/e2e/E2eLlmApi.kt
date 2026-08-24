package ru.souz.backend.e2e

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.llms.local.LocalProviderStatus

internal class E2eLlmApi : LLMChatAPI {
    val requests = CopyOnWriteArrayList<LLMRequest.Chat>()
    val streamedChunks = CopyOnWriteArrayList<String>()
    private val gates = LinkedHashMap<String, CompletableDeferred<Unit>>()
    private val mutex = Mutex()
    private var failMessage: String? = null
    private var hang = false
    private var releaseGate: CompletableDeferred<Unit>? = null
    private var streamingChunks: List<String>? = null
    private var streamFailureMessage: String? = null
    private var hangAfterStreaming = false
    private var skill: SkillScript? = null
    private val promptReleaseGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val promptSkills = ConcurrentHashMap<String, SkillScript>()

    fun failWith(message: String) {
        failMessage = message
    }

    fun hangUntilCancelled() {
        hang = true
    }

    fun pauseUntilReleased() {
        releaseGate = CompletableDeferred()
    }

    fun release() {
        releaseGate?.complete(Unit)
    }

    fun streamChunks(chunks: List<String>) {
        streamingChunks = chunks
    }

    fun streamThenFail(chunks: List<String>, message: String) {
        streamingChunks = chunks
        streamFailureMessage = message
    }

    fun streamThenHang(chunks: List<String>) {
        streamingChunks = chunks
        hangAfterStreaming = true
    }

    fun requestSkill(skillId: String, arguments: Map<String, Any>) {
        skill = SkillScript(skillId, arguments)
    }

    fun requestSkillForPrompt(prompt: String, skillId: String, arguments: Map<String, Any>) {
        promptSkills[prompt] = SkillScript(skillId, arguments)
    }

    fun pausePromptUntilReleased(prompt: String) {
        promptReleaseGates[prompt] = CompletableDeferred()
    }

    fun releasePrompt(prompt: String) {
        promptReleaseGates[prompt]?.complete(Unit)
    }

    suspend fun awaitPrompt(prompt: String) {
        signal(prompt).await()
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        requests += body
        val prompt = body.conversationPrompt()
        signal(prompt).complete(Unit)
        failMessage?.let { error(it) }
        if (hang) awaitCancellation()
        releaseGate?.await()
        promptReleaseGates[prompt]?.await()
        return (promptSkills[prompt] ?: skill)?.let { scriptedSkillReply(body, it) }
            ?: reply(body, "assistant reply to $prompt")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        requests += body
        val prompt = body.conversationPrompt()
        signal(prompt).complete(Unit)
        failMessage?.let { error(it) }
        releaseGate?.await()
        promptReleaseGates[prompt]?.await()
        (promptSkills[prompt] ?: skill)?.let {
            emit(scriptedSkillReply(body, it))
            return@flow
        }
        val chunks = streamingChunks ?: listOf("assistant ", "reply ", "to $prompt")
        chunks.forEachIndexed { index, content ->
            streamedChunks += content
            val finishesNormally = streamFailureMessage == null && !hangAfterStreaming
            emit(
                reply(
                    body = body,
                    content = content,
                    completionTokens = index + 1,
                    finishReason = LLMResponse.FinishReason.stop.takeIf {
                        finishesNormally && index == chunks.lastIndex
                    },
                )
            )
        }
        streamFailureMessage?.let { error(it) }
        if (hangAfterStreaming) awaitCancellation()
    }

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        LLMResponse.Embeddings.Ok(
            data = listOf(LLMResponse.Embedding(embedding = listOf(0.1, 0.2), index = 0)),
            model = body.model,
            objectType = "list",
        )

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is outside backend E2E scope.")

    override suspend fun downloadFile(fileId: String): String? = null

    override suspend fun balance(): LLMResponse.Balance =
        LLMResponse.Balance.Ok(emptyList())

    private suspend fun signal(prompt: String): CompletableDeferred<Unit> =
        mutex.withLock { gates.getOrPut(prompt) { CompletableDeferred() } }
}

private data class SkillScript(
    val skillId: String,
    val arguments: Map<String, Any>,
)

private fun scriptedSkillReply(
    body: LLMRequest.Chat,
    script: SkillScript,
): LLMResponse.Chat.Ok {
    val latestUserIndex = body.messages.indexOfLast { it.role == LLMMessageRole.user }
    val currentTurn = body.messages.drop(latestUserIndex.coerceAtLeast(0))
    return when {
        currentTurn.any { it.role == LLMMessageRole.function && it.name == "RunSkillCommand" } ->
            reply(body, "client tool completed")

        currentTurn.any { it.role == LLMMessageRole.function && it.name == "GetSkillByName" } ->
            toolCallReply(
                body = body,
                name = "RunSkillCommand",
                arguments = mapOf("skillId" to script.skillId, "arguments" to script.arguments),
            )

        else -> toolCallReply(body, "GetSkillByName", mapOf("skillId" to script.skillId))
    }
}

private fun toolCallReply(
    body: LLMRequest.Chat,
    name: String,
    arguments: Map<String, Any>,
): LLMResponse.Chat.Ok =
    LLMResponse.Chat.Ok(
        choices = listOf(
            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = "",
                    role = LLMMessageRole.assistant,
                    functionCall = LLMResponse.FunctionCall(name, arguments),
                    functionsStateId = "e2e-${name.lowercase()}",
                ),
                index = 0,
                finishReason = LLMResponse.FinishReason.function_call,
            )
        ),
        created = System.currentTimeMillis(),
        model = body.model,
        usage = LLMResponse.Usage(7, 3, 10, 0),
    )

internal fun LLMRequest.Chat.conversationPrompt(): String =
    messages.lastOrNull { message ->
        message.role == LLMMessageRole.user && !message.content.contains("<context>")
    }?.content.orEmpty()

internal fun reply(
    body: LLMRequest.Chat,
    content: String,
    completionTokens: Int = 3,
    finishReason: LLMResponse.FinishReason? = LLMResponse.FinishReason.stop,
): LLMResponse.Chat.Ok =
    LLMResponse.Chat.Ok(
        choices = listOf(
            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = content,
                    role = LLMMessageRole.assistant,
                    functionCall = null,
                    functionsStateId = null,
                ),
                index = 0,
                finishReason = finishReason,
            )
        ),
        created = System.currentTimeMillis(),
        model = body.model,
        usage = LLMResponse.Usage(7, completionTokens, 7 + completionTokens, 0),
    )

internal fun localChatApiBackedBy(delegate: LLMChatAPI): LocalChatAPI =
    mockk {
        coEvery { message(any()) } coAnswers { delegate.message(firstArg()) }
        coEvery { messageStream(any()) } coAnswers { delegate.messageStream(firstArg()) }
        coEvery { embeddings(any()) } coAnswers { delegate.embeddings(firstArg()) }
        coEvery { uploadFile(any()) } coAnswers { delegate.uploadFile(firstArg()) }
        coEvery { downloadFile(any()) } coAnswers { delegate.downloadFile(firstArg()) }
        coEvery { balance() } coAnswers { delegate.balance() }
    }

internal fun localProviderAvailability(): LocalProviderAvailability =
    mockk {
        every { isProviderAvailable() } returns true
        every { availableGigaModels() } returns listOf(E2E_LOCAL_MODEL)
        every { defaultGigaModel() } returns E2E_LOCAL_MODEL
        every { selectedProfile() } returns null
        every { status() } returns LocalProviderStatus(
            available = true,
            message = "OK (backend E2E)",
            selectedProfile = null,
            availableModels = listOf(E2E_LOCAL_MODEL),
        )
    }

internal fun relaxedLocalRuntime(): LocalLlamaRuntime =
    mockk(relaxed = true)
