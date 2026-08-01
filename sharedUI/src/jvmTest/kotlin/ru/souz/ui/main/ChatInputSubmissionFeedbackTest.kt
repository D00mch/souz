package ru.souz.ui.main

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatInputSubmissionFeedbackTest {
    private val pending = PendingChatInputSubmission(
        input = "keep this draft",
        afterRevision = 3L,
    )

    @Test
    fun `rejected matching submission resolves without accepting the draft`() {
        val accepted = ChatInputSubmissionFeedback(
            revision = 4L,
            input = "keep this draft",
            accepted = false,
        ).acceptanceFor(pending)

        assertFalse(accepted ?: error("Expected matching feedback"))
    }

    @Test
    fun `accepted matching submission allows the draft to be cleared`() {
        val accepted = ChatInputSubmissionFeedback(
            revision = 4L,
            input = "keep this draft",
            accepted = true,
        ).acceptanceFor(pending)

        assertTrue(accepted ?: error("Expected matching feedback"))
    }

    @Test
    fun `stale or unrelated feedback does not resolve the pending draft`() {
        assertNull(
            ChatInputSubmissionFeedback(
                revision = 3L,
                input = "keep this draft",
                accepted = true,
            ).acceptanceFor(pending)
        )
        assertNull(
            ChatInputSubmissionFeedback(
                revision = 4L,
                input = "another draft",
                accepted = true,
            ).acceptanceFor(pending)
        )
    }
}
