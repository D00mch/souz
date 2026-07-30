@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.memory

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopConversationMemoryRuntimeTest {
    @Test
    fun `captureCompletedTurn does not use desktop conversation id as chat scope`() = runTest {
        val memoryService = mockk<MemoryService>(relaxed = true)
        val captureService = mockk<MemoryCaptureService>()
        val inputSlot = slot<MemoryCaptureInput>()
        coEvery { captureService.captureAfterTurn(capture(inputSlot)) } returns emptyList()
        val runtime = DesktopConversationMemoryRuntime(
            memoryService,
            captureService,
            DesktopMemoryContextProvider(NoopDesktopMemoryProjectContextProvider),
        )

        runtime.captureCompletedTurn(
            CompletedTurnMemoryInput(
                conversationId = "chat-42",
                userMessageId = "user-1",
                assistantMessageId = "assistant-1",
                userMessage = "remember this",
                assistantMessage = "ok",
                evidence = listOf(
                    CompletedTurnEvidence(
                        kind = CompletedTurnEvidenceKind.TOOL_OUTPUT,
                        sourceName = "ToolTelegramGetHistory",
                        text = "Tool output with next steps.",
                    )
                ),
            )
        )

        assertEquals("chat-42", inputSlot.captured.context.conversationId?.value)
        assertEquals("chat-42", inputSlot.captured.context.sessionId?.value)
        assertEquals(
            listOf(
                CompletedTurnEvidence(
                    kind = CompletedTurnEvidenceKind.TOOL_OUTPUT,
                    sourceName = "ToolTelegramGetHistory",
                    text = "Tool output with next steps.",
                )
            ),
            inputSlot.captured.evidence,
        )
        assertEquals(
            listOf("global", "session"),
            inputSlot.captured.scopes.map { it.type },
        )
        assertEquals("chat-42", inputSlot.captured.scopes.single { it.type == "session" }.id)
        coVerify(exactly = 1) { captureService.captureAfterTurn(any()) }
    }
}
