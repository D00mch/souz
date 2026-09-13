package ru.souz.tool.subagent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.agent.Agent
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.SubagentTool
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.tool.ToolCategory
import ru.souz.tool.immutableToolCatalogFromLists
import ru.souz.tool.skills.SkillCommandExecutor
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolInvokeSkill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubagentToolFactoryTest {
    @Test
    fun `empty selection binds parent settings and returns a tool result`() = runTest {
        val fixture = Fixture()
        val parent = fixture.parent.copy(temperature = 0.27f, contextSize = 42_000)
        val tool = fixture.factory().create(parent)
        val meta = ToolInvocationMeta(
            userId = "owner", conversationId = "conversation", requestId = "request",
            locale = "ru", timeZone = "Europe/Moscow", attributes = mapOf("clientToolSessionId" to "client"),
        )

        val response = tool.invoke(LLMResponse.FunctionCall(tool.fn.name, mapOf("task" to "Summarize this text.")), meta)

        assertEquals(LLMMessageRole.function, response.role)
        assertEquals(SubagentTool.NAME, response.name)
        assertEquals("{\"result\":\"child answer\"}", response.content)
        assertEquals("Summarize this text.", fixture.context.input)
        assertEquals(parent.copy(tools = AgentTools(emptyMap())), fixture.context.settings)
        assertEquals(meta, fixture.context.toolInvocationMeta)
        assertTrue(fixture.context.activeTools.isEmpty())
        coVerify(exactly = 0) { fixture.bundles.loadSkillBundle(any(), any()) }
    }

    @Test
    fun `enabled compiled tools take precedence and selection preserves host schemas`() = runTest {
        val selected = namedTool("selected")
        val other = namedTool("other")
        val fixture = Fixture(listOf(selected, other))
        val filtered = object : LLMToolSetup by selected {
            override val fn = selected.fn.copy(description = "host customized")
        }
        every { fixture.filter.applyFilter(any()) } returns mapOf(ToolCategory.FILES to mapOf("selected" to filtered))

        fixture.factory().create(fixture.parent).call(mapOf("task" to "Inspect", "skillIds" to listOf("selected", "selected")))
        val setup = fixture.context

        assertEquals(listOf(filtered), setup.settings.tools.byName.values.toList())
        assertEquals(ToolCategory.FILES, setup.settings.tools.categoryByName["selected"])
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
        assertTrue(fixture.contexts.isEmpty())
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
        assertTrue(fixture.contexts.isEmpty())
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
        val setup = fixture.context
        assertTrue(setup.systemPrompt.contains("Approved task instructions."))
        assertTrue(setup.systemPrompt.contains("Fallback bundle."))
        val discovery = ToolGetSkillByName(fixture.catalog, fixture.filter, fixture.bundles)
            .call(mapOf("skillId" to "selected"), meta)
        val promptPayloads = setup.systemPrompt.lineSequence().filter { it.startsWith("{") }
            .map { restJsonMapper.readTree(it) }.toList()
        assertEquals(discovery["executionSchema"], promptPayloads[0])
        assertEquals(discovery["skill"], promptPayloads[1])
        assertEquals(listOf(ToolInvokeSkill.NAME), setup.settings.tools.byName.values.toList().map { it.fn.name })
        coVerify(exactly = 2) { approval.ensureApproved(any()) }

        // The child loader serves only the bundles selected at spawn without another registry lookup.
        coEvery { fixture.bundles.loadSkillBundle(any(), any()) } throws AssertionError("Unexpected live lookup")
        coEvery { fixture.commands.execute(any(), any(), any(), any()) } returns SandboxCommandResult(0, "ok", "", false)
        val command = setup.settings.tools.byName.values.toList().single()
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
        assertTrue(fixture.contexts.isEmpty())
        coVerify(exactly = 0) { fixture.commands.execute(any(), any(), any(), any()) }
    }

    @Test
    fun `without configuration only the parent model is advertised and accepted`() = runTest {
        val fixture = Fixture()
        val tool = fixture.factory().create(fixture.parent)
        assertEquals(listOf(fixture.parent.model), tool.fn.parameters.properties.getValue("model").enum)
        tool.call(mapOf("task" to "Inspect"))
        tool.call(mapOf("task" to "Inspect", "model" to fixture.parent.model))
        assertEquals(List(2) { fixture.parent.copy(tools = AgentTools(emptyMap())) }, fixture.contexts.map { it.settings })
        listOf("unknown", " ", LLMModel.Pro.alias, LLMModel.Max.name).forEach { model ->
            val response = tool.call(mapOf("task" to "Inspect", "model" to model, "skillIds" to listOf("unloaded")))
            assertEquals("subagent_model_unavailable", response["error"]["code"].asText())
        }
        assertEquals(2, fixture.contexts.size)
        coVerify(exactly = 0) { fixture.bundles.loadSkillBundle(any(), any()) }
    }

    @Test
    fun `configured models retain exact IDs and providers from the advertised snapshot`() = runTest {
        val fixture = Fixture()
        val models = linkedMapOf("My-Deployment/v2" to LlmProvider.OPENAI, " another-model " to LlmProvider.ANTHROPIC)
        val tool = fixture.factory(configuredModels = models).create(fixture.parent)
        val choices = models + (fixture.parent.model to fixture.parent.provider)
        models.clear()
        assertEquals(choices.keys.toList(), tool.fn.parameters.properties.getValue("model").enum)
        choices.forEach { (model, provider) ->
            tool.call(mapOf("task" to "Inspect", "model" to model))
            assertEquals(model, fixture.context.settings.model)
            assertEquals(provider, fixture.context.settings.provider)
        }
        val rejected = tool.call(mapOf("task" to "Inspect", "model" to "my-deployment/v2"))
        assertEquals("subagent_model_unavailable", rejected["error"]["code"].asText())
        assertEquals(choices.size, fixture.contexts.size)
        assertEquals(LLMModel.Max.alias, fixture.parent.model)
    }

    @Test
    fun `parent provider wins model ID collisions and omission always inherits`() = runTest {
        val fixture = Fixture()
        val parent = fixture.parent.copy(model = "gpt-5.4", provider = LlmProvider.CODEX)
        val tool = fixture.factory(configuredModels = mapOf("gpt-5.4" to LlmProvider.OPENAI)).create(parent)
        assertEquals(listOf(parent.model), tool.fn.parameters.properties.getValue("model").enum)
        tool.call(mapOf("task" to "Inspect"))
        tool.call(mapOf("task" to "Inspect", "model" to parent.model))
        assertEquals(List(2) { parent.copy(tools = AgentTools(emptyMap())) }, fixture.contexts.map { it.settings })
    }

    @Test
    fun `skill loading cancellation propagates without running child`() = runTest {
        val fixture = Fixture()
        coEvery { fixture.bundles.loadSkillBundle(any(), any()) } throws CancellationException("stop")
        assertFailsWith<CancellationException> {
            fixture.factory().create(fixture.parent).call(mapOf("task" to "Task", "skillIds" to listOf("skill")))
        }
        assertTrue(fixture.contexts.isEmpty())
    }

    private class Fixture(compiled: List<LLMToolSetup> = emptyList()) {
        val catalog = immutableToolCatalogFromLists(mapOf(ToolCategory.FILES to compiled))
        val parent = AgentSettings(LLMModel.Max.alias, LLMModel.Max.provider, 0.5f, AgentTools(catalog.toolsByCategory))
        val filter = mockk<AgentToolsFilter> { every { applyFilter(any()) } answers { firstArg() } }
        val bundles = mockk<SkillBundleProvider> { coEvery { loadSkillBundle(any(), any()) } returns null }
        val commands = mockk<SkillCommandExecutor>()
        val contexts = mutableListOf<AgentContext<String>>()
        val context get() = contexts.last()

        fun factory(
            approvalGate: SkillApprovalGate? = null,
            configuredModels: Map<String, LlmProvider> = emptyMap(),
        ) = SubagentToolFactory(
            { _ -> mockk<Agent> {
                coEvery { execute(any(), any(), any()) } answers {
                    contexts += firstArg<AgentContext<String>>()
                    AgentExecutionResult("child answer", context)
                }
            } },
            catalog, filter, bundles, commands, approvalGate, configuredModels,
        )
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
