package ru.souz.service.observability

import ru.souz.llms.LLMResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatObservabilityTrackerTest {
    @Test
    fun `defers conversation finish until active request completes`() {
        val started = mutableListOf<Pair<String, ChatRequestSource>>()
        val finished = mutableListOf<Triple<String, ChatConversationMetrics, ChatConversationCloseReason>>()
        val tracker = ChatObservabilityTracker(
            onConversationStarted = { conversationId, source ->
                started += conversationId to source
            },
            onConversationFinished = { conversationId, metrics, reason ->
                finished += Triple(conversationId, metrics, reason)
            },
        )

        val conversationId = tracker.ensureConversation(ChatRequestSource.CHAT_UI)
        tracker.markConversationRequestStarted(conversationId)
        tracker.recordConversationRequestFinished(
            conversationId = conversationId,
            toolCallCount = 2,
            requestTokenUsage = LLMResponse.Usage(3, 5, 8, 0),
        )
        tracker.finishCurrentConversation(ChatConversationCloseReason.CLEAR_CONTEXT)

        assertEquals(listOf(conversationId to ChatRequestSource.CHAT_UI), started)
        assertTrue(finished.isEmpty())

        tracker.finishPendingConversationIfNeeded(conversationId)

        val event = finished.single()
        assertEquals(conversationId, event.first)
        assertEquals(ChatConversationCloseReason.CLEAR_CONTEXT, event.third)
        assertEquals(1, event.second.requestCount)
        assertEquals(2, event.second.toolCallCount)
        assertEquals(8, event.second.tokenUsage.totalTokens)
    }

    @Test
    fun `view model cleared conversation finish is emitted after active request completes`() {
        val finished = mutableListOf<Triple<String, ChatConversationMetrics, ChatConversationCloseReason>>()
        val tracker = ChatObservabilityTracker(
            onConversationStarted = { _, _ -> },
            onConversationFinished = { conversationId, metrics, reason ->
                finished += Triple(conversationId, metrics, reason)
            },
        )

        val conversationId = tracker.ensureConversation(ChatRequestSource.CHAT_UI)
        tracker.markConversationRequestStarted(conversationId)
        tracker.finishCurrentConversation(ChatConversationCloseReason.VIEW_MODEL_CLEARED)

        assertTrue(finished.isEmpty())

        tracker.recordConversationRequestFinished(
            conversationId = conversationId,
            toolCallCount = 1,
            requestTokenUsage = LLMResponse.Usage(2, 3, 5, 0),
        )
        tracker.finishPendingConversationIfNeeded(conversationId)

        val event = finished.single()
        assertEquals(conversationId, event.first)
        assertEquals(ChatConversationCloseReason.VIEW_MODEL_CLEARED, event.third)
        assertEquals(1, event.second.requestCount)
        assertEquals(1, event.second.toolCallCount)
        assertEquals(5, event.second.tokenUsage.totalTokens)
    }
}
