package ru.souz.backend.client

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
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

class BackendClientToolCatalogTest {
    @Test
    fun `catalog exposes built-in client tools`() = runTest {
        val context = routeTestContext()

        val catalog = BackendClientToolCatalogFactory(
            registry = context.clientThreadRegistry,
            toolCallRepository = context.toolCallRepository,
            eventService = context.eventService,
        ).create()

        val ask = catalog.toolsByCategory.getValue(ToolCategory.CHAT).getValue("user.ask")
        val openMedia = catalog.toolsByCategory.getValue(ToolCategory.APPLICATIONS).getValue("device.media.open")
        assertEquals(setOf("user.ask", "device.media.open"), catalog.toolsByCategory.values.flatMap { it.keys }.toSet())
        assertContains(ask.fn.description, "Ask the user")
        assertEquals(setOf("question"), ask.fn.parameters.properties.keys)
        assertEquals(listOf("question"), ask.fn.parameters.required)
        assertContains(openMedia.fn.description, "Open media")
        assertEquals(setOf("query", "genre"), openMedia.fn.parameters.properties.keys)
        assertEquals(listOf("query"), openMedia.fn.parameters.required)
    }

    @Test
    fun `catalog rejects non-positive client tool timeouts`() {
        val context = routeTestContext()

        listOf(
            "zero user ask timeout" to {
                BackendClientToolCatalogFactory(
                    registry = context.clientThreadRegistry,
                    toolCallRepository = context.toolCallRepository,
                    eventService = context.eventService,
                    userAskTimeout = Duration.ZERO,
                )
            },
            "negative user ask timeout" to {
                BackendClientToolCatalogFactory(
                    registry = context.clientThreadRegistry,
                    toolCallRepository = context.toolCallRepository,
                    eventService = context.eventService,
                    userAskTimeout = Duration.ofMillis(-1),
                )
            },
            "zero device media timeout" to {
                BackendClientToolCatalogFactory(
                    registry = context.clientThreadRegistry,
                    toolCallRepository = context.toolCallRepository,
                    eventService = context.eventService,
                    deviceMediaOpenTimeout = Duration.ZERO,
                )
            },
            "negative device media timeout" to {
                BackendClientToolCatalogFactory(
                    registry = context.clientThreadRegistry,
                    toolCallRepository = context.toolCallRepository,
                    eventService = context.eventService,
                    deviceMediaOpenTimeout = Duration.ofMillis(-1),
                )
            },
        ).forEach { (caseName, createFactory) ->
            assertFailsWith<IllegalArgumentException>(caseName) {
                createFactory()
            }
        }
    }

    @Test
    fun `client tools reject missing and non-string required arguments before starting a call`() = runTest {
        val context = routeTestContext()
        val userId = UUID.randomUUID().toString()
        val chat = context.chatService.createClient(userId, "create-invalid-arguments", "backend", null).chat
        val catalog = BackendClientToolCatalogFactory(
            registry = context.clientThreadRegistry,
            toolCallRepository = context.toolCallRepository,
            eventService = context.eventService,
        ).create()
        val cases = listOf(
            InvalidArgumentsCase(ToolCategory.CHAT, "user.ask", "question", emptyMap()),
            InvalidArgumentsCase(ToolCategory.CHAT, "user.ask", "question", mapOf("question" to 42)),
            InvalidArgumentsCase(ToolCategory.APPLICATIONS, "device.media.open", "query", emptyMap()),
            InvalidArgumentsCase(ToolCategory.APPLICATIONS, "device.media.open", "query", mapOf("query" to false)),
            InvalidArgumentsCase(
                ToolCategory.APPLICATIONS,
                "device.media.open",
                "genre",
                mapOf("query" to "The Thing", "genre" to false),
            ),
        )

        cases.forEach { case ->
            val threadId = UUID.randomUUID()
            context.clientThreadRegistry.register(
                threadId,
                ClientDevice(userId, "device-tv", "tv_box", setOf("device_tools")),
            )
            val tool = catalog.toolsByCategory.getValue(case.category).getValue(case.toolName)

            val result = tool.invoke(
                LLMResponse.FunctionCall(case.toolName, case.arguments),
                ToolInvocationMeta(userId, chat.id.toString(), threadId.toString()),
            )

            val error = restJsonMapper.readTree(result.content)["error"]
            assertEquals(LLMMessageRole.function, result.role, case.toString())
            assertEquals(case.toolName, result.name, case.toString())
            assertEquals("invalid_arguments", error["code"].asText(), case.toString())
            assertContains(
                charSequence = error["message"].asText(),
                other = case.invalidArgument,
                message = case.toString(),
            )
            assertEquals(
                emptyList(),
                context.toolCallRepository.listByExecution(
                    ToolCallContext(userId, chat.id.toString(), threadId.toString(), "unused"),
                    limit = 10,
                ),
                case.toString(),
            )
            val probeToolCallId = UUID.randomUUID().toString()
            assertTrue(
                context.clientThreadRegistry.beginTool(threadId, PendingClientTool(probeToolCallId))
                    is BeginClientToolResult.Started,
                case.toString(),
            )
            context.clientThreadRegistry.clearTool(threadId, probeToolCallId)
        }
        assertTrue(context.eventRepository.listByChat(userId, chat.id).isEmpty())
    }

    @Test
    fun `client tool recovers a persisted terminal result when the local ack is lost`() = runTest {
        val context = routeTestContext()
        val repository = TerminalRaceToolCallRepository()
        val userId = UUID.randomUUID().toString()
        val chat = context.chatService.createClient(userId, "create-1", "backend", null).chat
        val threadId = UUID.randomUUID()
        context.clientThreadRegistry.register(
            threadId,
            ClientDevice(userId, "device-tv", "tv_box", setOf("speech", "screen", "device_tools")),
        )
        val catalog = BackendClientToolCatalogFactory(
            registry = context.clientThreadRegistry,
            toolCallRepository = repository,
            eventService = context.eventService,
            userAskTimeout = Duration.ofMillis(1),
        ).create()
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
}

private data class InvalidArgumentsCase(
    val category: ToolCategory,
    val toolName: String,
    val invalidArgument: String,
    val arguments: Map<String, Any>,
)

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
