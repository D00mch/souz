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
    fun `captureCompletedTurn uses chat scope as primary scope when conversation id is present`() = runTest {
        val memoryService = mockk<MemoryService>(relaxed = true)
        val captureService = mockk<MemoryCaptureService>()
        val inputSlot = slot<MemoryCaptureInput>()
        coEvery { captureService.captureAfterTurn(capture(inputSlot)) } returns emptyList()
        val runtime = DesktopConversationMemoryRuntime(memoryService, captureService)

        runtime.captureCompletedTurn(
            CompletedTurnMemoryInput(
                conversationId = "chat-42",
                userMessageId = "user-1",
                assistantMessageId = "assistant-1",
                userMessage = "remember this",
                assistantMessage = "ok",
            )
        )

        assertEquals(MemoryScope("chat", "chat-42"), inputSlot.captured.primaryScope)
        assertEquals(
            listOf(MemoryScope("global", "global"), MemoryScope("chat", "chat-42")),
            inputSlot.captured.scopes,
        )
        coVerify(exactly = 1) { captureService.captureAfterTurn(any()) }
    }
}
