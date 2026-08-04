package ru.souz.backend.client

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.registry.StoredSkill
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

class BackendClientToolCatalogTest {
    @Test
    fun `catalog projects bundled client Skills`() = runTest {
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
        assertContains(openMedia.fn.description, "Open media")
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
        val catalog = BackendClientToolCatalogFactory(
            skillBundleProvider = ShortTimeoutClientSkillBundleProvider,
            registry = context.clientThreadRegistry,
            toolCallRepository = repository,
            eventService = context.eventService,
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

private object ShortTimeoutClientSkillBundleProvider : SkillBundleProvider {
    private val bundle = SkillBundle.fromFiles(
        skillId = SkillId("user.ask"),
        files = listOf(
            SkillFile(
                normalizedPath = "SKILL.md",
                content = """
                    ---
                    name: user-ask
                    description: Ask the user.
                    metadata:
                      souz.skill-id: user.ask
                      souz.transport: client-websocket
                      souz.category: CHAT
                      souz.timeout: PT0.001S
                    ---

                    # Ask the user
                """.trimIndent().toByteArray(),
            )
        ),
    )

    override suspend fun listSkills(userId: String): List<StoredSkill> = listOf(stored(userId))

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? =
        stored(userId).takeIf { it.skillId == skillId }

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? =
        stored(userId).takeIf { it.manifest.name == name }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? =
        bundle.takeIf { it.skillId == skillId }

    private fun stored(userId: String): StoredSkill =
        StoredSkill(
            userId = userId,
            skillId = bundle.skillId,
            manifest = bundle.manifest,
            bundleHash = SkillBundleHasher.hash(bundle),
            createdAt = Instant.EPOCH,
        )
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
