package ru.souz.tool.subagent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.SubagentRunner
import ru.souz.agent.SubagentTurnLimitException
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.tool.ToolCategory
import ru.souz.tool.immutableToolCatalogFromLists
import ru.souz.tool.skills.SkillCommandExecutor
import ru.souz.tool.skills.ToolInvokeSkill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolSpawnSubagentTest {
    @Test
    fun `empty selection binds parent settings and preserves complete metadata`() = runTest {
        val fixture = Fixture()
        val parent = fixture.parent.copy(temperature = 0.27f, contextSize = 42_000)
        val tool = fixture.factory().create(parent)
        val meta = ToolInvocationMeta(
            userId = "owner", conversationId = "conversation", requestId = "request",
            locale = "ru", timeZone = "Europe/Moscow", attributes = mapOf("clientToolSessionId" to "client"),
        )

        val response = tool.invoke(LLMResponse.FunctionCall(tool.fn.name, mapOf("task" to "Summarize this text.")), meta)

        assertEquals(LLMMessageRole.function, response.role)
        assertEquals(ToolSpawnSubagent.NAME, response.name)
        assertEquals("{\"result\":\"child answer\"}", response.content)
        assertEquals("Summarize this text.", fixture.task)
        assertEquals(parent.model, fixture.childSettings.model)
        assertEquals(parent.temperature, fixture.childSettings.temperature)
        assertEquals(parent.contextSize, fixture.childSettings.contextSize)
        assertSame(meta, fixture.meta)
        assertTrue(fixture.childSettings.tools.byName.isEmpty())
        assertTrue(fixture.tools.isEmpty())
        assertEquals(32, fixture.maxTurns)
        coVerify(exactly = 0) { fixture.bundles.loadSkillBundle(any(), any()) }
    }

    @Test
    fun `enabled compiled tools take precedence and only selected filtered schemas are executable`() = runTest {
        val selected = namedTool("selected")
        val other = namedTool("other")
        val fixture = Fixture(listOf(selected, other))
        val filtered = object : LLMToolSetup by selected {
            override val fn = selected.fn.copy(description = "host customized")
        }
        every { fixture.filter.applyFilter(any()) } returns mapOf(ToolCategory.FILES to mapOf("selected" to filtered))

        fixture.factory().create(fixture.parent).call(mapOf("task" to "Inspect", "skillIds" to listOf("selected", "selected")))

        assertEquals(listOf(filtered), fixture.tools)
        assertEquals(mapOf("selected" to filtered), fixture.childSettings.tools.byName)
        assertEquals(ToolCategory.FILES, fixture.childSettings.tools.categoryByName["selected"])
        coVerify(exactly = 0) { fixture.bundles.loadSkillBundle(any(), any()) }
    }

    @Test
    fun `unavailable selections reject entire child including disabled tools`() = runTest {
        val fixture = Fixture(listOf(namedTool("enabled"), namedTool("disabled")))
        every { fixture.filter.applyFilter(any()) } answers {
            firstArg<Map<ToolCategory, Map<String, LLMToolSetup>>>().mapValues { (_, tools) -> tools - "disabled" }
        }
        val tool = fixture.factory().create(fixture.parent)

        listOf("missing" to "skill_not_found", "disabled" to "skill_disabled", "  " to "invalid_skill_id")
            .forEach { (id, code) ->
                val response = tool.call(mapOf("task" to "Inspect", "skillIds" to listOf("enabled", id)))
                assertEquals(code, response["error"]["code"].asText())
            }
        coVerify(exactly = 0) { fixture.runner.execute(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `spawning and generic discovery or execution cannot be selected even from catalog`() = runTest {
        val forbidden = listOf("SpawnSubagent", "RunSkillCommand", "GetSkillByName", "GetSkillsByCategory", "GetSkillsNamesByCategory")
        val fixture = Fixture(forbidden.map(::namedTool))
        val tool = fixture.factory().create(fixture.parent)

        forbidden.forEach { id ->
            val response = tool.call(mapOf("task" to "Escape", "skillIds" to listOf(id)))
            assertEquals("skill_not_allowed", response["error"]["code"].asText())
        }
        coVerify(exactly = 0) { fixture.runner.execute(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.bundles.loadSkillBundle(any(), any()) }
    }

    @Test
    fun `approved file skills preload instructions and commands stay within selected skills`() = runTest {
        val selected = bundle("selected", "Approved task instructions.")
        val fixture = Fixture(listOf(namedTool("disabled")))
        every { fixture.filter.applyFilter(any()) } returns emptyMap()
        coEvery { fixture.bundles.loadSkillBundle(any(), SkillId("disabled")) } returns bundle("disabled", "Fallback bundle.")
        coEvery { fixture.bundles.loadSkillBundle(any(), SkillId("selected")) } returns selected
        val approval = mockk<SkillApprovalGate>()
        coEvery { approval.ensureApproved(any()) } answers {
            val input = firstArg<SkillApprovalGate.Input>()
            SkillApprovalGate.Result.Approved(input.bundle, SkillBundleHasher.hash(input.bundle), null)
        }
        val meta = ToolInvocationMeta("owner", "conversation", attributes = mapOf("routing" to "session"))
        fixture.factory(approval).create(fixture.parent).call(
            mapOf("task" to "Inspect", "skillIds" to listOf("selected", "disabled")), meta,
        )
        assertTrue(fixture.systemPrompt.contains("Approved task instructions."))
        assertTrue(fixture.systemPrompt.contains("Fallback bundle."))
        assertTrue(fixture.systemPrompt.contains("inputSchema"))
        assertTrue(fixture.systemPrompt.contains("scriptPath"))
        assertEquals(listOf(ToolInvokeSkill.NAME), fixture.tools.map { it.fn.name })
        coVerify(exactly = 2) { approval.ensureApproved(any()) }

        // The child provider serves only the bundles selected at spawn without another registry lookup.
        coEvery { fixture.bundles.loadSkillBundle(any(), any()) } throws AssertionError("Unexpected live lookup")
        coEvery { fixture.commands.execute(any(), any(), any(), any()) } returns SandboxCommandResult(0, "ok", "", false)
        val command = fixture.tools.single()
        assertEquals(listOf("selected", "disabled"), command.fn.parameters.properties.getValue("skillId").enum)
        assertFalse(command.fn.description.contains("GetSkillByName"))
        val success = command.call(mapOf("skillId" to "selected", "arguments" to mapOf("script" to "pwd")), meta)
        val denied = command.call(mapOf("skillId" to "unselected", "arguments" to mapOf("script" to "pwd")), meta)
        val wrongOwner = command.call(mapOf("skillId" to "selected"), ToolInvocationMeta("another-owner"))

        assertEquals("ok", success["stdout"].asText())
        assertEquals("skill_not_found", denied["error"]["code"].asText())
        assertEquals("skill_not_found", wrongOwner["error"]["code"].asText())
        coVerify(exactly = 1) { fixture.commands.execute(selected, SkillBundleHasher.hash(selected), any(), meta) }
        coVerify(exactly = 2) { approval.ensureApproved(any()) }
    }

    @Test
    fun `rejected bundle is never shown or executed`() = runTest {
        val fixture = Fixture()
        coEvery { fixture.bundles.loadSkillBundle(any(), any()) } returns bundle("rejected", "Hidden instructions.")
        val approval = mockk<SkillApprovalGate>()
        coEvery { approval.ensureApproved(any()) } returns SkillApprovalGate.Result.Rejected("hash", "Rejected by policy", emptyList())

        val response = fixture.factory(approval).create(fixture.parent)
            .call(mapOf("task" to "Inspect", "skillIds" to listOf("rejected")))

        assertEquals("skill_validation_rejected", response["error"]["code"].asText())
        assertFalse(response.toString().contains("Hidden instructions."))
        coVerify(exactly = 0) { fixture.runner.execute(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.commands.execute(any(), any(), any(), any()) }
    }

    @Test
    fun `model override is available within parent provider and never changes application settings`() = runTest {
        val fixture = Fixture()
        val tool = fixture.factory(availableModels = { listOf(LLMModel.Max, LLMModel.Pro) }).create(fixture.parent)

        tool.call(mapOf("task" to "Inspect", "model" to "gigachat-2-pro", "maxTurns" to 1))

        assertEquals(LLMModel.Pro.alias, fixture.childSettings.model)
        assertEquals(1, fixture.maxTurns)
        assertEquals(LLMModel.Max.alias, fixture.parent.model)
        listOf("unknown", " ", LLMModel.OpenAIGpt52.name, LLMModel.Lite.name).forEach { model ->
            val response = tool.call(mapOf("task" to "Inspect", "model" to model))
            assertEquals("subagent_model_unavailable", response["error"]["code"].asText())
        }
        coVerify(exactly = 1) { fixture.runner.execute(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invalid task or turn limits fail before child runs`() = runTest {
        val fixture = Fixture()
        val tool = fixture.factory().create(fixture.parent)

        listOf(
            emptyMap(), mapOf("task" to " "), mapOf("task" to "Task", "maxTurns" to 0),
            mapOf("task" to "Task", "maxTurns" to 129), mapOf("task" to "Task", "skillIds" to 2),
        ).forEach { arguments ->
            val response = tool.call(arguments)
            assertEquals("invalid_subagent_input", response["error"]["code"].asText())
        }
        coVerify(exactly = 0) { fixture.runner.execute(any(), any(), any(), any(), any(), any()) }
        tool.call(mapOf("task" to "Task", "maxTurns" to 128))
        assertEquals(128, fixture.maxTurns)
    }

    @Test
    fun `turn limit and execution errors become one failure result and cancellation propagates`() = runTest {
        val fixture = Fixture()
        val tool = fixture.factory().create(fixture.parent)
        coEvery { fixture.runner.execute(any(), any(), any(), any(), any(), any()) } throws SubagentTurnLimitException(2)
        assertEquals("subagent_turn_limit", tool.call(mapOf("task" to "Task"))["error"]["code"].asText())

        coEvery { fixture.runner.execute(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("Provider unavailable")
        val failed = tool.call(mapOf("task" to "Task"))
        assertEquals("subagent_failed", failed["error"]["code"].asText())
        assertEquals("Provider unavailable", failed["error"]["message"].asText())

        coEvery { fixture.runner.execute(any(), any(), any(), any(), any(), any()) } throws CancellationException("stop")
        assertFailsWith<CancellationException> { tool.call(mapOf("task" to "Task")) }
        coVerify(exactly = 3) { fixture.runner.execute(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skill loading cancellation propagates without running child`() = runTest {
        val fixture = Fixture()
        coEvery { fixture.bundles.loadSkillBundle(any(), any()) } throws CancellationException("stop")
        assertFailsWith<CancellationException> {
            fixture.factory().create(fixture.parent).call(mapOf("task" to "Task", "skillIds" to listOf("skill")))
        }
        coVerify(exactly = 0) { fixture.runner.execute(any(), any(), any(), any(), any(), any()) }
    }

    private class Fixture(compiled: List<LLMToolSetup> = emptyList()) {
        val catalog = immutableToolCatalogFromLists(mapOf(ToolCategory.FILES to compiled))
        val parent = AgentSettings(LLMModel.Max.alias, 0.5f, AgentTools(catalog.toolsByCategory))
        val settings = mockk<AgentSettingsProvider> { every { gigaModel } returns LLMModel.Max }
        val filter = mockk<AgentToolsFilter> { every { applyFilter(any()) } answers { firstArg() } }
        val bundles = mockk<SkillBundleProvider> { coEvery { loadSkillBundle(any(), any()) } returns null }
        val commands = mockk<SkillCommandExecutor>()
        lateinit var task: String
        lateinit var childSettings: AgentSettings
        lateinit var systemPrompt: String
        lateinit var meta: ToolInvocationMeta
        var tools = emptyList<LLMToolSetup>()
        var maxTurns = 0
        val runner = mockk<SubagentRunner> {
            coEvery { execute(any(), any(), any(), any(), any(), any()) } answers {
                task = firstArg()
                childSettings = secondArg()
                tools = thirdArg()
                systemPrompt = arg(3)
                meta = arg(4)
                maxTurns = arg(5)
                AgentExecutionResult("child answer", mockk())
            }
        }

        fun factory(
            approvalGate: SkillApprovalGate? = null,
            availableModels: () -> List<LLMModel> = { LLMModel.entries },
        ) = SubagentToolFactory(runner, settings, catalog, filter, bundles, commands, approvalGate, availableModels)
    }
}

private fun namedTool(name: String): LLMToolSetup = mockk {
    every { fn } returns LLMRequest.Function(name, "Description $name", LLMRequest.Parameters("object"))
}

private fun bundle(id: String, instructions: String): SkillBundle = SkillBundle.fromFiles(
    SkillId(id), listOf(SkillFile("SKILL.md", "---\nname: $id\ndescription: Test skill\n---\n$instructions".toByteArray())),
)

private suspend fun LLMToolSetup.call(
    arguments: Map<String, Any>,
    meta: ToolInvocationMeta = ToolInvocationMeta("owner"),
) = restJsonMapper.readTree(invoke(LLMResponse.FunctionCall(fn.name, arguments), meta).content)
