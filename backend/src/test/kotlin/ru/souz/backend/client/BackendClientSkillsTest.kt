package ru.souz.backend.client

import java.io.InputStream
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
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

        val clientSkills = BackendClientSkills(
            registry = context.clientThreadRegistry,
            toolCallRepository = context.toolCallRepository,
            eventService = context.eventService,
        )

        val ask = clientSkills.toolsByCategory.getValue(ToolCategory.CHAT).getValue("user.ask")
        val openMedia = clientSkills.toolsByCategory
            .getValue(ToolCategory.APPLICATIONS)
            .getValue("device.media.open")
        assertEquals(
            setOf("user.ask", "device.media.open"),
            clientSkills.toolsByCategory.values.flatMap { it.keys }.toSet(),
        )
        assertEquals(
            setOf(SkillId("user.ask"), SkillId("device.media.open")),
            clientSkills.bundleHashesBySkillId.keys,
        )
        assertContains(ask.fn.description, "Ask the user")
        assertContains(ask.fn.description, "RunSkillCommand")
        assertEquals("object", ask.fn.parameters.type)
        assertTrue(ask.fn.parameters.properties.isEmpty())
        assertTrue(ask.fn.parameters.required.isEmpty())
        assertContains(openMedia.fn.description, "Open media")
        assertEquals("object", openMedia.fn.parameters.type)
        assertTrue(openMedia.fn.parameters.properties.isEmpty())
        assertTrue(openMedia.fn.parameters.required.isEmpty())
    }

    @Test
    fun `bundled client Skills validate index and metadata eagerly`() {
        val context = routeTestContext()
        val invalidResources = listOf(
            "missing index" to emptyMap(),
            "unsafe index entry" to mapOf(
                "skills/client/index.txt" to "../user-ask\n",
            ),
            "duplicate index entry" to mapOf(
                "skills/client/index.txt" to "user-ask\nuser-ask\n",
            ),
            "missing indexed resource" to mapOf(
                "skills/client/index.txt" to "user-ask\n",
            ),
            "malformed bundle" to mapOf(
                "skills/client/index.txt" to "user-ask\n",
                "skills/client/user-ask/SKILL.md" to "---\nname: broken\ndescription: broken\n",
            ),
            "missing canonical ID" to mapOf(
                "skills/client/index.txt" to "user-ask\n",
                "skills/client/user-ask/SKILL.md" to clientSkillMarkdown(skillId = ""),
            ),
            "invalid transport" to mapOf(
                "skills/client/index.txt" to "user-ask\n",
                "skills/client/user-ask/SKILL.md" to clientSkillMarkdown(transport = "filesystem"),
            ),
            "invalid category" to mapOf(
                "skills/client/index.txt" to "user-ask\n",
                "skills/client/user-ask/SKILL.md" to clientSkillMarkdown(category = "UNKNOWN"),
            ),
            "invalid timeout" to mapOf(
                "skills/client/index.txt" to "user-ask\n",
                "skills/client/user-ask/SKILL.md" to clientSkillMarkdown(timeout = "PT0S"),
            ),
            "duplicate canonical ID" to mapOf(
                "skills/client/index.txt" to "first\nsecond\n",
                "skills/client/first/SKILL.md" to clientSkillMarkdown(name = "first"),
                "skills/client/second/SKILL.md" to clientSkillMarkdown(name = "second"),
            ),
        )

        invalidResources.forEach { (caseName, resources) ->
            assertFailsWith<IllegalArgumentException>(caseName) {
                BackendClientSkills(
                    registry = context.clientThreadRegistry,
                    toolCallRepository = context.toolCallRepository,
                    eventService = context.eventService,
                    classLoader = TestResourceClassLoader(resources),
                )
            }
        }
    }

    @Test
    fun `client Skill recovers a persisted terminal result when the local ack is lost`() = runTest {
        val context = routeTestContext()
        val repository = TerminalRaceToolCallRepository()
        val userId = UUID.randomUUID().toString()
        val chat = context.chatService.createClient(userId, "create-1", "backend", null).chat
        val threadId = UUID.randomUUID()
        context.clientThreadRegistry.register(
            threadId,
            ClientDevice(userId, "device-tv", "tv_box", setOf("speech", "screen", "device_tools")),
        )
        val baseTime = Instant.parse("2026-01-01T00:00:00Z")
        var currentTime = baseTime
        val clientSkills = BackendClientSkills(
            registry = context.clientThreadRegistry,
            toolCallRepository = repository,
            eventService = context.eventService,
            now = {
                currentTime.also {
                    currentTime = baseTime.plusSeconds(600)
                }
            },
        )
        val tool = clientSkills.toolsByCategory.getValue(ToolCategory.CHAT).getValue("user.ask")

        val result = withTimeout(2_000) {
            tool.invoke(
                LLMResponse.FunctionCall(
                    "user.ask",
                    mapOf("question" to mapOf("nested" to 1), "extra" to listOf(true, 2)),
                ),
                ToolInvocationMeta(userId, chat.id.toString(), threadId.toString()),
            )
        }

        assertEquals(LLMMessageRole.function, result.role)
        assertEquals("user.ask", result.name)
        assertEquals("""{"answer":"stored"}""", result.content)
        assertEquals(ToolCallStatus.SUCCEEDED, repository.storedStatus)
        assertEquals(
            restJsonMapper.readTree("""{"question":{"nested":1},"extra":[true,2]}"""),
            restJsonMapper.readTree(repository.startedArgumentsJson),
        )
    }

    @Test
    fun `invoke without meta returns client context unavailable`() = runTest {
        val context = routeTestContext()
        val clientSkills = BackendClientSkills(
            registry = context.clientThreadRegistry,
            toolCallRepository = context.toolCallRepository,
            eventService = context.eventService,
        )
        val tool = clientSkills.toolsByCategory.getValue(ToolCategory.CHAT).getValue("user.ask")

        val result = tool.invoke(LLMResponse.FunctionCall("user.ask", mapOf("question" to "Ready?")))
        val error = restJsonMapper.readTree(result.content)["error"]

        assertEquals(LLMMessageRole.function, result.role)
        assertEquals("user.ask", result.name)
        assertEquals("client_context_missing", error["code"].asText())
        assertEquals("Client tool context is unavailable.", error["message"].asText())
    }
}

private class TestResourceClassLoader(
    resources: Map<String, String>,
) : ClassLoader(null) {
    private val resources = resources.mapValues { (_, content) -> content.toByteArray(Charsets.UTF_8) }

    override fun getResourceAsStream(name: String): InputStream? = resources[name]?.inputStream()
}

private fun clientSkillMarkdown(
    name: String = "user-ask",
    skillId: String = "user.ask",
    transport: String = "client-websocket",
    category: String = "CHAT",
    timeout: String = "PT5M",
): String = """
    ---
    name: $name
    description: Test client Skill.
    metadata:
      souz.skill-id: $skillId
      souz.transport: $transport
      souz.category: $category
      souz.timeout: $timeout
    ---

    # Test client Skill
""".trimIndent()

private class TerminalRaceToolCallRepository(
    private val delegate: MemoryToolCallRepository = MemoryToolCallRepository(),
) : ToolCallRepository by delegate {
    var storedStatus: ToolCallStatus? = null
        private set
    var startedArgumentsJson: String? = null
        private set

    override suspend fun startClientCall(
        context: ToolCallContext,
        name: String,
        deviceId: String?,
        argumentsJson: String,
        deadlineAt: Instant,
        startedAt: Instant,
    ): ToolCall {
        startedArgumentsJson = argumentsJson
        return delegate.startClientCall(
            context = context,
            name = name,
            deviceId = deviceId,
            argumentsJson = argumentsJson,
            deadlineAt = deadlineAt,
            startedAt = startedAt,
        )
    }

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
