package ru.souz.backend.e2e

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
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
    private val gates = LinkedHashMap<String, CompletableDeferred<Unit>>()
    private val mutex = Mutex()
    private var failMessage: String? = null
    private var hang = false
    private var releaseGate: CompletableDeferred<Unit>? = null
    private var streamingChunks: List<String>? = null

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
        return reply(body, "assistant reply to $prompt")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        requests += body
        val prompt = body.conversationPrompt()
        signal(prompt).complete(Unit)
        failMessage?.let { error(it) }
        val chunks = streamingChunks ?: listOf("assistant ", "reply ", "to $prompt")
        chunks.forEachIndexed { index, content ->
            emit(reply(body, content, completionTokens = index + 1))
        }
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

internal fun LLMRequest.Chat.conversationPrompt(): String =
    messages.lastOrNull { message ->
        message.role == LLMMessageRole.user && !message.content.contains("<context>")
    }?.content.orEmpty()

internal fun reply(
    body: LLMRequest.Chat,
    content: String,
    completionTokens: Int = 3,
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
                finishReason = LLMResponse.FinishReason.stop,
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
