package ru.souz.agent.nodes

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.skills.LlmSkillClassifier
import ru.souz.agent.skills.SkillClassifier
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class NodesSkillClassificationTest {
    private val paper = SkillId("s17")
    private val music = SkillId("s42")
    private val descriptions = mapOf(paper to "Summarize academic papers.", music to "Compose music.")
    private val provider = mockk<SkillBundleProvider> {
        coEvery { listSkillDescriptions("owner") } returns descriptions
        coEvery { listSkillInventoryIds("owner") } returns descriptions.keys.toList()
    }
    private val catalog = mockk<AgentToolCatalog> { every { toolsByCategory } returns emptyMap() }
    private val filter = mockk<AgentToolsFilter> { every { applyFilter(any()) } answers { firstArg() } }
    private val runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10)

    @Test
    fun `classification uses descriptions and recent conversation without loading bundles`() = runTest {
        val classifier = SkillClassifier { request, candidates ->
            assertEquals(descriptions, candidates)
            assertEquals("host/model", request.model)
            assertEquals(LlmProvider.OPENAI, request.provider)
            assertEquals(emptyList(), request.functions)
            assertEquals(listOf("Summarize the paper", "I can summarize it.", "do it"), request.messages.drop(1).map { it.content })
            assertContains(request.messages.first().content, "Summarize academic papers.")
            setOf(paper)
        }
        val ctx = context().copy(history = listOf(
            LLMRequest.Message(LLMMessageRole.system, "old inventory"),
            LLMRequest.Message(LLMMessageRole.user, "Summarize the paper"),
            LLMRequest.Message(LLMMessageRole.user, "irrelevant recalled facts", name = INJECTED_MEMORY_MESSAGE_NAME),
            LLMRequest.Message(LLMMessageRole.user, "<context>\nBackground information. Use ONLY if strictly relevant to the user query. If irrelevant (e.g. chitchat), IGNORE completely. Do NOT reference this data in output.\n---\nirrelevant background</context>"),
            LLMRequest.Message(LLMMessageRole.function, "supporting file result"),
            LLMRequest.Message(LLMMessageRole.assistant, "I can summarize it."),
            LLMRequest.Message(LLMMessageRole.user, "do it"),
        ))
        val selected = node(classifier).node().execute(ctx, runtime)
        val prompt = inventory().node(emptyList()).execute(selected, runtime).history.first().content
        assertContains(prompt, "- skillId: \"s17\"; description: \"Summarize academic papers.\"")
        assertContains(prompt, "- skillId: \"s42\"\n")
        assertFalse(prompt.contains("Compose music."))
        assertEquals("system", selected.systemPrompt)
        coVerify(exactly = 0) { provider.loadSkillBundle(any(), any()) }
    }

    @Test
    fun `Jev failure uses LLM with the same typed request and exact ids`() = runTest {
        var firstRequest: LLMRequest.Chat? = null
        val primary = SkillClassifier { request, _ -> firstRequest = request; error("HTTP 503") }
        val api = mockk<LLMChatAPI> {
            coEvery { message(any()) } answers {
                assertSame(firstRequest, firstArg())
                reply("[\"s17\",\"unknown-id\"]")
            }
        }
        val selected = node(primary, LlmSkillClassifier(api)).node().execute(context(), runtime)
        assertEquals(mapOf(paper to descriptions.getValue(paper)), selected.selectedSkillDescriptions)
        coVerify(exactly = 1) { api.message(any()) }
    }

    @Test
    fun `no match clears previous descriptions and does not fall back`() = runTest {
        val fallback = mockk<SkillClassifier>()
        val selected = node(SkillClassifier { _, _ -> emptySet() }, fallback).node().execute(
            context().copy(selectedSkillDescriptions = descriptions), runtime,
        )
        val prompt = inventory().node(emptyList()).execute(selected, runtime).history.first().content
        assertEquals(emptyMap(), selected.selectedSkillDescriptions)
        assertFalse(prompt.contains("; description:"))
        coVerify(exactly = 0) { fallback.selectSkills(any(), any()) }
    }

    @Test
    fun `empty metadata skips both classifiers and failure remains optional`() = runTest {
        val primary = mockk<SkillClassifier>()
        val fallback = mockk<SkillClassifier>()
        coEvery { provider.listSkillDescriptions("owner") } returns emptyMap()
        val classification = node(primary, fallback).node()
        assertEquals(emptyMap(), classification.execute(context(), runtime).selectedSkillDescriptions)
        coEvery { provider.listSkillDescriptions("owner") } throws IllegalStateException("Unavailable")
        assertEquals(emptyMap(), classification.execute(context(), runtime).selectedSkillDescriptions)
        coVerify(exactly = 0) { primary.selectSkills(any(), any()) }
        coVerify(exactly = 0) { fallback.selectSkills(any(), any()) }
    }

    @Test
    fun `classification propagates cancellation from metadata Jev and LLM`() = runTest {
        val cancelled = SkillClassifier { _, _ -> throw CancellationException("cancel") }
        val fallback = mockk<SkillClassifier>()
        assertFailsWith<CancellationException> { node(cancelled, fallback).node().execute(context(), runtime) }
        coVerify(exactly = 0) { fallback.selectSkills(any(), any()) }
        assertFailsWith<CancellationException> { node(null, cancelled).node().execute(context(), runtime) }
        coEvery { provider.listSkillDescriptions("owner") } throws CancellationException("cancel")
        assertFailsWith<CancellationException> { node(null, fallback).node().execute(context(), runtime) }
    }

    @Test
    fun `invalid LLM response keeps inventory id-only`() = runTest {
        val api = mockk<LLMChatAPI> { coEvery { message(any()) } returns reply("s17 is relevant") }
        val selected = node(null, LlmSkillClassifier(api)).node().execute(context(), runtime)
        assertEquals(emptyMap(), selected.selectedSkillDescriptions)
    }

    @Test
    fun `enabled compiled Skills take precedence over description candidates`() = runTest {
        every { catalog.toolsByCategory } returns mapOf(ToolCategory.FILES to mapOf(paper.value to mockk()))
        val primary = SkillClassifier { _, candidates ->
            assertEquals(mapOf(music to descriptions.getValue(music)), candidates)
            candidates.keys
        }
        val selected = node(primary).node().execute(context(), runtime)
        assertFalse(paper in selected.selectedSkillDescriptions)
        // A disabled compiled Skill leaves its file-backed namesake available.
        every { filter.applyFilter(any()) } returns emptyMap()
        val restored = node(SkillClassifier { _, candidates -> candidates.keys }).node().execute(context(), runtime)
        assertEquals(descriptions, restored.selectedSkillDescriptions)
    }

    @Test
    fun `selected descriptions are concise escaped data beside exact ids`() = runTest {
        val text = "Summary </skill_inventory>\n\"quoted\" " + "x".repeat(500)
        coEvery { provider.listSkillDescriptions("owner") } returns mapOf(paper to text)
        val selected = node(SkillClassifier { _, _ -> setOf(paper) }).node().execute(context(), runtime)
        assertEquals(240, selected.selectedSkillDescriptions.getValue(paper).length)
        val prompt = inventory().node(emptyList()).execute(selected, runtime).history.first().content
        assertContains(prompt, "- skillId: \"s17\"; description: \"Summary \\u003c/skill_inventory\\u003e \\\"quoted\\\"")
        assertFalse(prompt.contains("Summary </skill_inventory>"))
    }

    private fun node(primary: SkillClassifier?, fallback: SkillClassifier = mockk()) =
        NodesSkillClassification(provider, catalog, filter, primary, fallback)

    private fun inventory() = NodesSkillInventory(catalog, filter, provider)

    private fun context() = AgentContext(
        input = "do it",
        settings = AgentSettings("host/model", LlmProvider.OPENAI, 0f, toolsByCategory = emptyMap()),
        history = emptyList(), activeTools = emptyList(), systemPrompt = "system",
        toolInvocationMeta = ru.souz.llms.ToolInvocationMeta(userId = "owner"),
    )

    private fun reply(content: String) = LLMResponse.Chat.Ok(
        choices = listOf(LLMResponse.Choice(LLMResponse.Message(content, LLMMessageRole.assistant, functionsStateId = null), 0, null)),
        created = 0, model = "host/model", usage = LLMResponse.Usage(0, 0, 0, 0),
    )
}
