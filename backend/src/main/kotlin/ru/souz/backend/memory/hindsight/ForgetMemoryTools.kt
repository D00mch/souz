package ru.souz.backend.memory.hindsight

import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.model.OptionKind
import ru.souz.backend.options.model.OptionStatus
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.storage.postgres.PostgresHistoryMemoryRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

internal val FORGET_MEMORY_TOOL_NAMES = setOf(ToolForgetMemoryFact.NAME, ToolForgetAllMemory.NAME)

private const val CAPTURE_NOTE = "Remembering needs no tool: every turn is saved to memory automatically after your " +
    "reply, so confirm requests to remember. Only a turn that calls this tool is not saved; if it also shares something " +
    "new to remember, ask the user to repeat it separately. Never restate forgotten information in later replies."
private const val CONFIRMATION_PURPOSE = "forget_all_memory"
private val CONFIRMATION_TTL = Duration.ofMinutes(10)
private val logger = LoggerFactory.getLogger(ToolForgetMemoryFact::class.java)

internal class ToolForgetMemoryFact(
    private val memory: HindsightConversationMemoryRuntime,
    private val fragments: PostgresHistoryMemoryRepository,
) : LLMToolSetup {
    override val fn = LLMRequest.Function(
        name = NAME,
        description = "Forget exactly one persistent memory fact by its factId from SearchMemory. Use only when the user " +
            "explicitly asks to forget that information. If several facts match or none clearly does, ask the user instead " +
            "of guessing; never forget merely related facts. forgotten=true confirms it. pendingCapture=true means recent " +
            "dialogue is still being saved and may store it again: tell the user and suggest repeating the request in a " +
            "couple of minutes. $CAPTURE_NOTE",
        parameters = LLMRequest.Parameters("object", mapOf(
            "factId" to LLMRequest.Property("string", "Exact factId returned by SearchMemory."),
        ), required = listOf("factId"), additionalProperties = false),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall) = failure(NAME, "memory_context_missing")

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
        val factId = (functionCall.arguments["factId"] as? String)?.trim()?.takeIf(String::isNotEmpty)
            ?: return failure(NAME, "invalid_arguments", "factId must be a non-blank string.")
        return forgetting(NAME) {
            // Checked before forgetting so that a failed check never hides a completed change.
            val pending = fragments.hasPending(meta.userId) || memory.hasPendingRetain(meta.userId)
            if (memory.forgetFact(meta.userId, factId)) {
                response(NAME, mapOf("forgotten" to true, "pendingCapture" to pending))
            } else {
                failure(NAME, "not_found", "The user's memory has no fact with this factId; nothing was forgotten.")
            }
        }
    }

    companion object {
        const val NAME = "ForgetMemoryFact"
    }
}

internal class ToolForgetAllMemory(
    private val memory: HindsightConversationMemoryRuntime,
    private val fragments: PostgresHistoryMemoryRepository,
    private val options: OptionRepository,
    private val clock: Clock,
) : LLMToolSetup {
    override val fn = LLMRequest.Function(
        name = NAME,
        description = "Forget all persistent memory of the user in every conversation. Use only when the user explicitly " +
            "asks to forget everything. Without confirmationId nothing is deleted and the result returns one: ask the user " +
            "to confirm and end your turn. Call again with it only after the user explicitly confirms in a later message " +
            "within 10 minutes; each confirmationId works once. forgotten=true confirms deletion. $CAPTURE_NOTE",
        parameters = LLMRequest.Parameters("object", mapOf(
            "confirmationId" to LLMRequest.Property("string", "confirmationId from the previous ForgetAllMemory result."),
        ), additionalProperties = false),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall) = failure(NAME, "memory_context_missing")

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
        val chatId = meta.conversationId.toUuid()
        val executionId = meta.requestId.toUuid()
        if (chatId == null || executionId == null) return failure(NAME, "memory_context_missing")
        val confirmationId = functionCall.arguments["confirmationId"]
        val requested = (confirmationId as? String).toUuid()
        return forgetting(NAME) {
            when {
                confirmationId == null -> {
                    val now = clock.instant()
                    // Without items, the generic option-answer route cannot answer this confirmation.
                    val confirmation = options.save(Option(
                        id = UUID.randomUUID(), userId = meta.userId, chatId = chatId, executionId = executionId,
                        kind = OptionKind.TOOL_CONFIRMATION, title = null, selectionMode = "single", options = emptyList(),
                        payload = mapOf("purpose" to CONFIRMATION_PURPOSE), status = OptionStatus.PENDING, answer = null,
                        createdAt = now, expiresAt = now.plus(CONFIRMATION_TTL), answeredAt = null,
                    ))
                    response(NAME, mapOf(
                        "forgotten" to false,
                        "confirmationId" to confirmation.id.toString(),
                        "message" to "Nothing was deleted. Ask the user to confirm in a new message.",
                    ))
                }
                requested != null && options.consumeToolConfirmation(
                    meta.userId, chatId, requested, CONFIRMATION_PURPOSE, executionId, clock.instant(),
                ) -> {
                    memory.forgetAll(meta.userId) { fragments.forget(meta.userId) }
                    // An older unused confirmation must not delete memory saved after this.
                    options.cancelToolConfirmations(meta.userId, CONFIRMATION_PURPOSE)
                    response(NAME, mapOf("forgotten" to true))
                }
                else -> failure(NAME, "confirmation_invalid",
                    "Nothing was deleted. Request a new confirmationId and ask the user to confirm in a new message.")
            }
        }
    }

    companion object {
        const val NAME = "ForgetAllMemory"
    }
}

private suspend fun forgetting(name: String, block: suspend () -> LLMRequest.Message): LLMRequest.Message = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: IOException) {
    logger.warn("{} failed category={}", name, error.javaClass.simpleName)
    failure(name, "memory_unavailable", "Persistent memory is unreachable; forgetting did not complete.")
} catch (error: Exception) {
    logger.warn("{} failed category={}", name, error.javaClass.simpleName)
    failure(name, "forget_failed", "Forgetting did not complete.")
}

private fun failure(name: String, code: String, message: String = "Forgetting is unavailable in this context.") =
    response(name, mapOf("forgotten" to false, "error" to mapOf("code" to code, "message" to message)))

private fun response(name: String, value: Map<String, Any>) =
    LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(value), name = name)

private fun String?.toUuid(): UUID? = this?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
