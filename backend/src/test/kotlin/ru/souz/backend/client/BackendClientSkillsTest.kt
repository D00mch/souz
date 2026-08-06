package ru.souz.backend.client

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory

class BackendClientSkillsTest {
    @Test
    fun `catalog projects bundled client Skills`() = runTest {
        val context = routeTestContext()

        val catalog = BackendClientSkills(
            registry = context.clientThreadRegistry,
            toolCallRepository = context.toolCallRepository,
            eventService = context.eventService,
        )

        val ask = catalog.toolsByCategory.getValue(ToolCategory.CHAT).getValue("user.ask")
        val openMedia = catalog.toolsByCategory.getValue(ToolCategory.APPLICATIONS).getValue("device.media.open")
        assertEquals(setOf("device.media.open", "user.ask"), catalog.skillIds)
        assertEquals(setOf("user.ask", "device.media.open"), catalog.toolsByCategory.values.flatMap { it.keys }.toSet())
        assertEquals(listOf(SkillId("device.media.open"), SkillId("user.ask")), catalog.listSkillInventoryIds("user-a"))
        assertEquals("user-a", catalog.getSkill("user-a", SkillId("user.ask"))?.userId)
        assertContains(ask.fn.description, "Ask the user")
        assertContains(openMedia.fn.description, "Open media")
    }

    @Test
    fun `client Skill recovers a persisted terminal result when the local ack is lost`() = runTest {
        val context = routeTestContext()
        val repository = TerminalRaceToolCallRepository()
        val now = Instant.parse("2026-01-01T00:00:00Z")
        var current = now
        val userId = UUID.randomUUID().toString()
        val chat = context.chatService.createClient(userId, "create-1", "backend", null).chat
        val threadId = UUID.randomUUID()
        context.clientThreadRegistry.register(
            threadId,
            ClientDevice(userId, "device-tv", "tv_box", setOf("speech", "screen", "device_tools")),
        )
        val catalog = BackendClientSkills(
            registry = context.clientThreadRegistry,
            toolCallRepository = repository,
            eventService = context.eventService,
            now = {
                current.also {
                    current = now.plusSeconds(600)
                }
            },
        )
        val tool = catalog.toolsByCategory.getValue(ToolCategory.CHAT).getValue("user.ask")

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

    @Test
    fun `invoke without meta returns client context unavailable`() = runTest {
        val context = routeTestContext()

        val catalog = BackendClientSkills(
            registry = context.clientThreadRegistry,
            toolCallRepository = context.toolCallRepository,
            eventService = context.eventService,
        )
        val tool = catalog.toolsByCategory.getValue(ToolCategory.CHAT).getValue("user.ask")
        val result = tool.invoke(LLMResponse.FunctionCall("user.ask", mapOf("question" to "Ready?")))
        val error = restJsonMapper.readTree(result.content)

        assertEquals("user.ask", result.name)
        assertEquals("client_context_missing", error["error"]["code"].asText())
        assertEquals("Client tool context is unavailable.", error["error"]["message"].asText())
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
