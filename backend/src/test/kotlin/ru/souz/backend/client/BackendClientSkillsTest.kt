package ru.souz.backend.client

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.activation.SkillId
import ru.souz.backend.http.routeTestContext
import ru.souz.backend.testutil.repository.MemoryToolCallRepository
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolCategory

class BackendClientSkillsTest {
    @Test
    fun `client Skills expose bundled resources and websocket adapters`() = runTest {
        val context = routeTestContext()

        val clientSkills = BackendClientSkills(
            registry = context.clientThreadRegistry,
            toolCallRepository = context.toolCallRepository,
            eventService = context.eventService,
        )

        val ask = clientSkills.toolsByCategory.getValue(ToolCategory.CHAT).getValue("user.ask")
        val openMedia = clientSkills.toolsByCategory.getValue(ToolCategory.APPLICATIONS).getValue("device.media.open")
        val loadedAsk = assertNotNull(clientSkills.loadSkillBundle("user-a", SkillId("user.ask")))
        assertEquals(setOf("user.ask", "device.media.open"), clientSkills.skillIds)
        assertEquals(
            listOf(SkillId("device.media.open"), SkillId("user.ask")),
            clientSkills.listSkillInventoryIds("user-a"),
        )
        assertEquals("user-a", clientSkills.getSkill("user-a", SkillId("user.ask"))?.userId)
        assertEquals("user.ask", loadedAsk.skillId.value)
        assertContains(ask.fn.description, "Ask the user")
        assertContains(ask.fn.description, "# Ask the user")
        assertContains(openMedia.fn.description, "Open media")
        assertContains(openMedia.fn.description, "# Open media")
    }

    @Test
    fun `client Skill recovers a persisted terminal result when the local ack is lost`() = runTest {
        val context = routeTestContext()
        val repository = TerminalRaceToolCallRepository()
        val startedAt = Instant.parse("2026-01-01T00:00:00Z")
        var current = startedAt
        val userId = UUID.randomUUID().toString()
        val chat = context.chatService.createClient(userId, "create-1", "backend", null).chat
        val threadId = UUID.randomUUID()
        context.clientThreadRegistry.register(
            threadId,
            ClientDevice(userId, "device-tv", "tv_box", setOf("speech", "screen", "device_tools")),
        )
        val clientSkills = BackendClientSkills(
            registry = context.clientThreadRegistry,
            toolCallRepository = repository,
            eventService = context.eventService,
            now = {
                current.also {
                    current = startedAt.plusSeconds(10 * 60)
                }
            },
        )
        val tool = clientSkills.toolsByCategory.getValue(ToolCategory.CHAT).getValue("user.ask")

        val result = withTimeout(2_000) {
            tool.invoke(
                LLMResponse.FunctionCall("user.ask", mapOf("question" to "Ready?")),
                ToolInvocationMeta(userId, chat.id.toString(), threadId.toString()),
            )
        }

        assertEquals(LLMMessageRole.function, result.role)
        assertEquals("user.ask", result.name)
        assertEquals("""{"answer":"stored"}""", result.content)
        assertEquals(ToolCallStatus.SUCCEEDED, repository.storedStatus)
    }
}

private class TerminalRaceToolCallRepository(
    private val delegate: MemoryToolCallRepository = MemoryToolCallRepository(),
) : ToolCallRepository by delegate {
    var storedStatus: ToolCallStatus? = null
        private set

    override suspend fun completeClientCall(
        context: ToolCallContext,
        status: ToolCallStatus,
        resultJson: String?,
        errorJson: String?,
        payloadHash: String,
        receivedAt: Instant,
    ): ToolCall? {
        val stored = delegate.completeClientCall(
            context = context,
            status = ToolCallStatus.SUCCEEDED,
            resultJson = """{"answer":"stored"}""",
            errorJson = null,
            payloadHash = "stored-result",
            receivedAt = receivedAt,
        )
        storedStatus = stored?.status
        return null
    }
}
