package ru.souz.ui.main.usecases

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.MainState
import ru.souz.ui.main.ToolModifyReviewItemUi
import ru.souz.ui.main.ToolModifyReviewUi
import ru.souz.tool.files.DeferredToolModifyPermissionBroker
import ru.souz.tool.files.ToolModifyApplyResult
import ru.souz.tool.files.ToolModifyApplyStatus
import ru.souz.tool.files.ToolModifySelectionAction
import java.util.concurrent.atomic.AtomicReference

class ToolModifyReviewUseCase(
    private val deferredToolModifyPermissionBroker: DeferredToolModifyPermissionBroker,
) {
    private val _outputs = MutableSharedFlow<MainUseCaseOutput>(replay = 1, extraBufferCapacity = 32)
    val outputs: Flow<MainUseCaseOutput> = _outputs.asSharedFlow()

    private val pendingToolModifyReview = AtomicReference<PendingToolModifyReviewState?>(null)

    fun hasPendingEdits(): Boolean = deferredToolModifyPermissionBroker.hasPendingEdits()

    /**
     * Runs the deferred tool-modify approval flow when the broker has pending edits.
     */
    suspend fun resolvePendingReviewIfNeeded(
        requestId: Long,
        pendingBotMessage: ChatMessage,
        response: String,
        onReviewShown: (messageId: String) -> Unit = {},
    ): ToolModifyReviewResult {
        val pendingReview = deferredToolModifyPermissionBroker.snapshotPendingReview()
            ?: return ToolModifyReviewResult(text = response, appendAsNewMessage = false)

        val reviewMessage = pendingBotMessage.copy(
            toolModifyReview = ToolModifyReviewUi(
                items = pendingReview.items.map { item ->
                    ToolModifyReviewItemUi(
                        id = item.id,
                        path = item.path,
                        patchPreview = item.patchPreview,
                    )
                }
            )
        )
        val reviewDeferred = CompletableDeferred<ToolModifyReviewDecision>()
        pendingToolModifyReview.set(
            PendingToolModifyReviewState(
                requestId = requestId,
                messageId = reviewMessage.id,
                decision = reviewDeferred,
            )
        )
        onReviewShown(reviewMessage.id)

        emitState {
            copy(
                chatMessages = upsertMessage(reviewMessage, fallbackMessageId = pendingBotMessage.id),
                isProcessing = false,
                isAwaitingToolReview = true,
                agentActions = emptyList(),
            )
        }

        val decision = reviewDeferred.await()
        val applyResult = deferredToolModifyPermissionBroker.applySelection(
            selectedIds = decision.selectedIds,
            action = decision.action,
        )
        val resolvedReview = reviewMessage.toolModifyReview?.copy(
            items = reviewMessage.toolModifyReview.items.map { item ->
                val outcome = applyResult.items.firstOrNull { it.id == item.id }
                item.copy(
                    selected = item.id in decision.selectedIds,
                    status = outcome?.status,
                    warning = outcome?.warning,
                )
            },
            isResolved = true,
            summary = formatToolModifyReviewSummary(applyResult),
        )
        emitState {
            copy(
                chatMessages = chatMessages.map { message ->
                    if (message.id == reviewMessage.id) {
                        message.copy(toolModifyReview = resolvedReview)
                    } else {
                        message
                    }
                },
                isAwaitingToolReview = false,
            )
        }
        pendingToolModifyReview.set(null)

        return ToolModifyReviewResult(
            text = appendToolModifySummary(response, applyResult),
            appendAsNewMessage = true,
        )
    }

    /**
     * Toggles selection state for a single review item in the pending approval UI.
     */
    suspend fun toggleSelection(messageId: String, itemId: Long) {
        val pendingReviewState = pendingToolModifyReview.get() ?: return
        if (pendingReviewState.messageId != messageId) return
        emitState {
            copy(
                chatMessages = chatMessages.map { message ->
                    if (message.id != messageId) return@map message
                    val review = message.toolModifyReview ?: return@map message
                    message.copy(
                        toolModifyReview = review.copy(
                            items = review.items.map { item ->
                                if (item.id == itemId && !review.isResolved) {
                                    item.copy(selected = !item.selected)
                                } else {
                                    item
                                }
                            }
                        )
                    )
                }
            )
        }
    }

    /**
     * Completes the current pending approval request with the selected action.
     */
    fun resolve(
        messageId: String,
        action: ToolModifySelectionAction,
        selectedIds: Set<Long>,
    ) {
        val pendingReviewState = pendingToolModifyReview.get() ?: return
        if (pendingReviewState.messageId != messageId) return
        pendingReviewState.decision.complete(
            ToolModifyReviewDecision(
                action = action,
                selectedIds = selectedIds,
            )
        )
    }

    /**
     * Cancels any pending review and optionally discards broker state.
     */
    suspend fun clearPendingReview(discardBrokerState: Boolean) {
        pendingToolModifyReview.getAndSet(null)?.decision?.cancel()
        if (discardBrokerState) {
            withContext(NonCancellable) {
                deferredToolModifyPermissionBroker.clearPending()
            }
        }
    }

    fun clearPendingReviewBlocking(discardBrokerState: Boolean) {
        runBlocking {
            clearPendingReview(discardBrokerState = discardBrokerState)
        }
    }

    private suspend fun emitState(reduce: MainState.() -> MainState) {
        _outputs.emit(MainUseCaseOutput.State(reduce))
    }

    private fun MainState.upsertMessage(
        message: ChatMessage,
        fallbackMessageId: String? = null,
    ): List<ChatMessage> = when {
        chatMessages.lastOrNull()?.id == message.id -> chatMessages.mapLast { message }
        fallbackMessageId != null && chatMessages.lastOrNull()?.id == fallbackMessageId ->
            chatMessages.mapLast { message }
        else -> chatMessages + message
    }

    private inline fun <T> List<T>.mapLast(transform: (T) -> T): List<T> =
        mapIndexed { index, value -> if (index == lastIndex) transform(value) else value }

    private fun appendToolModifySummary(
        response: String,
        applyResult: ToolModifyApplyResult,
    ): String {
        val summary = buildString {
            appendLine()
            appendLine()
            appendLine("Applied changes summary:")
            appendLine("- Applied: ${applyResult.appliedCount}")
            appendLine("- Discarded: ${applyResult.discardedCount}")
            appendLine("- Skipped: ${applyResult.skippedCount}")
            applyResult.items
                .filter { it.status == ToolModifyApplyStatus.SKIPPED_CONFLICT || it.status == ToolModifyApplyStatus.SKIPPED_EXTERNAL_CONFLICT }
                .forEach { item ->
                    appendLine("- Warning: ${item.path}: ${item.warning ?: "Can't apply the staged change."}")
                }
        }
        return response + summary
    }

    private fun formatToolModifyReviewSummary(applyResult: ToolModifyApplyResult): String =
        "Applied ${applyResult.appliedCount}, discarded ${applyResult.discardedCount}, skipped ${applyResult.skippedCount}"

    data class ToolModifyReviewResult(
        val text: String,
        val appendAsNewMessage: Boolean,
    )

    private data class PendingToolModifyReviewState(
        val requestId: Long,
        val messageId: String,
        val decision: CompletableDeferred<ToolModifyReviewDecision>,
    )

    private data class ToolModifyReviewDecision(
        val action: ToolModifySelectionAction,
        val selectedIds: Set<Long>,
    )
}
