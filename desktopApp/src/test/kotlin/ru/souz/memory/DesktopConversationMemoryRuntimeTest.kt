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
    fun `search replaces caller owner and uses global plus current session scopes`() = runTest {
        val memoryService = mockk<MemoryService>()
        val contextSlot = slot<MemoryContext>()
        val scopesSlot = slot<List<MemoryScope>>()
        coEvery {
            memoryService.searchMemory(
                capture(contextSlot),
                "User testing preferences",
                listOf("tests first"),
                4,
                capture(scopesSlot),
            )
        } returns emptyList()
        val runtime = DesktopConversationMemoryRuntime(
            memoryService = memoryService,
            captureService = mockk(relaxed = true),
            contextProvider = DesktopMemoryContextProvider(
                ownerProvider = MemoryOwnerProvider { MemoryOwnerId("desktop-owner") },
                projectContextProvider = object : DesktopMemoryProjectContextProvider {
                    override fun currentProjectId(): ProjectId = ProjectId("project-3")
                },
            ),
        )

        runtime.searchMemory(
            context = MemoryContext(
                MemoryOwnerId("caller-owner"), ConversationId("conversation-7"),
                MemorySessionId("wrong-session"), ProjectId("caller-project"),
            ),
            semanticQuery = "User testing preferences",
            lexicalHints = listOf("tests first"),
            maxFacts = 4,
        )

        assertEquals("desktop-owner", contextSlot.captured.ownerId.value)
        assertEquals("conversation-7", contextSlot.captured.conversationId?.value)
        assertEquals("conversation-7", contextSlot.captured.sessionId?.value)
        assertEquals(
            listOf(globalMemoryScope(), MemoryScope.session(MemorySessionId("conversation-7"))),
            scopesSlot.captured,
        )
    }

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
