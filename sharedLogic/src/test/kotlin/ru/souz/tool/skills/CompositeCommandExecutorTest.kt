package ru.souz.tool.skills

import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositeCommandExecutorTest {
    private val meta = ToolInvocationMeta(userId = "user-1")

    private fun meta(vararg allowedTools: String): ToolInvocationMeta =
        if (allowedTools.isEmpty()) meta else meta.copy(
            attributes = mapOf(ToolInvocationMeta.ACTIVE_TOOL_NAMES_ATTRIBUTE to allowedTools.joinToString(",")),
        )

    @Test
    fun `runs a tool step then a script step, threading a nested field between them`() = runTest {
        val screenshotTool = FakeTool("device.mcp.call_tool") { arguments ->
            assertEquals("get_screenshot", arguments["name"])
            assertEquals("tv-1", arguments["target"])
            """{"content":[{"uri":"https://example/shot.webp"}]}"""
        }
        val scriptCalls = mutableListOf<List<String>>()
        val executor = CompositeCommandExecutor(
            toolCatalog = catalog(ToolCategory.FILES to listOf(screenshotTool)),
            toolsFilter = TestToolsFilter(),
            runScript = { _, _, args, _ ->
                scriptCalls += args.args
                SandboxCommandResult(exitCode = 0, stdout = """{"x_norm":0.5,"y_norm":0.5}""", stderr = "")
            },
        )
        val bundle = bundle(
            "tv-control",
            """
            locate:
              inputs: [device, target]
              steps:
                - id: screenshot
                  tool: device.mcp.call_tool
                  arguments:
                    name: get_screenshot
                    target: "${'$'}{inputs.device}"
                - id: located
                  script: scripts/vision_locate.py
                  runtime: PYTHON
                  args: ["${'$'}{screenshot.content[0].uri}", "${'$'}{inputs.target}"]
              returns: "${'$'}{located}"
            """.trimIndent(),
        )

        val result = executor.execute(
            bundle = bundle,
            bundleHash = SkillBundleHasher.hash(bundle),
            commandName = "locate",
            inputs = mapOf("device" to "tv-1", "target" to "search icon"),
            meta = meta("device.mcp.call_tool"),
        )

        assertEquals(0, result.exitCode)
        assertEquals(listOf("https://example/shot.webp", "search icon"), scriptCalls.single())
        val returned = restJsonMapper.readTree(result.stdout)
        assertEquals(0.5, returned["x_norm"].asDouble())
    }

    @Test
    fun `a whole-reference argument keeps its JSON type instead of being stringified`() = runTest {
        val actTool = FakeTool("device.mcp.call_tool") { arguments ->
            @Suppress("UNCHECKED_CAST")
            val nested = arguments["arguments"] as Map<String, Any>
            assertEquals("el_1", nested["id"])
            """{"ok":true}"""
        }
        val executor = CompositeCommandExecutor(
            toolCatalog = catalog(ToolCategory.FILES to listOf(actTool)),
            toolsFilter = TestToolsFilter(),
            runScript = { _, _, _, _ -> error("no script step in this test") },
        )
        val bundle = bundle(
            "tv-control",
            """
            act:
              inputs: [action, actionArguments]
              steps:
                - id: act
                  tool: device.mcp.call_tool
                  arguments:
                    name: "${'$'}{inputs.action}"
                    arguments: "${'$'}{inputs.actionArguments}"
              returns: "${'$'}{act}"
            """.trimIndent(),
        )

        executor.execute(
            bundle = bundle,
            bundleHash = SkillBundleHasher.hash(bundle),
            commandName = "act",
            inputs = mapOf("action" to "click_element", "actionArguments" to """{"id":"el_1"}"""),
            meta = meta("device.mcp.call_tool"),
        )
    }

    @Test
    fun `a waitMs step delays without calling any tool and does not break the chain`() = runTest {
        val recordedDelays = mutableListOf<Long>()
        val observeTool = FakeTool("device.mcp.call_tool") { """{"screen":"home"}""" }
        val executor = CompositeCommandExecutor(
            toolCatalog = catalog(ToolCategory.FILES to listOf(observeTool)),
            toolsFilter = TestToolsFilter(),
            runScript = { _, _, _, _ -> error("no script step in this test") },
            delayFn = { ms -> recordedDelays += ms },
        )
        val bundle = bundle(
            "tv-control",
            """
            act_and_observe:
              inputs: [device]
              steps:
                - id: act
                  tool: device.mcp.call_tool
                  arguments:
                    name: tap_at
                - id: settle
                  waitMs: 1500
                - id: observe
                  tool: device.mcp.call_tool
                  arguments:
                    name: get_screen
              returns: "${'$'}{observe}"
            """.trimIndent(),
        )

        val result = executor.execute(
            bundle = bundle,
            bundleHash = SkillBundleHasher.hash(bundle),
            commandName = "act_and_observe",
            inputs = mapOf("device" to "tv-1"),
            meta = meta("device.mcp.call_tool"),
        )

        assertEquals(listOf(1500L), recordedDelays)
        assertEquals(0, result.exitCode)
        assertEquals("home", restJsonMapper.readTree(result.stdout)["screen"].asText())
    }

    @Test
    fun `a tool step exception stops the chain before later steps run`() = runTest {
        var secondStepCalled = false
        val failingTool = FakeTool("device.mcp.call_tool") { throw RuntimeException("device offline") }
        val secondTool = FakeTool("other.tool") { secondStepCalled = true; "{}" }
        val executor = CompositeCommandExecutor(
            toolCatalog = catalog(ToolCategory.FILES to listOf(failingTool, secondTool)),
            toolsFilter = TestToolsFilter(),
            runScript = { _, _, _, _ -> error("no script step in this test") },
        )
        val bundle = bundle(
            "tv-control",
            """
            broken:
              steps:
                - id: first
                  tool: device.mcp.call_tool
                - id: second
                  tool: other.tool
              returns: "${'$'}{second}"
            """.trimIndent(),
        )

        val result = executor.execute(
            bundle = bundle,
            bundleHash = SkillBundleHasher.hash(bundle),
            commandName = "broken",
            inputs = emptyMap(),
            meta = meta("device.mcp.call_tool", "other.tool"),
        )

        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("first"))
        assertTrue(result.stderr.contains("device offline"))
        assertTrue(!secondStepCalled)
    }

    @Test
    fun `a non-zero script exit stops the chain`() = runTest {
        val executor = CompositeCommandExecutor(
            toolCatalog = catalog(),
            toolsFilter = TestToolsFilter(),
            runScript = { _, _, _, _ -> SandboxCommandResult(exitCode = 2, stdout = "", stderr = "boom") },
        )
        val bundle = bundle(
            "tv-control",
            """
            fails:
              steps:
                - id: run
                  script: scripts/whatever.py
                  runtime: PYTHON
              returns: "${'$'}{run}"
            """.trimIndent(),
        )

        val result = executor.execute(
            bundle = bundle,
            bundleHash = SkillBundleHasher.hash(bundle),
            commandName = "fails",
            inputs = emptyMap(),
            meta = meta,
        )

        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("boom"))
    }

    @Test
    fun `a restricted tool name is rejected without invoking it`() = runTest {
        var invoked = false
        val runSkillCommand = FakeTool(ToolInvokeSkill.NAME) { invoked = true; "{}" }
        val executor = CompositeCommandExecutor(
            toolCatalog = catalog(ToolCategory.FILES to listOf(runSkillCommand)),
            toolsFilter = TestToolsFilter(),
            runScript = { _, _, _, _ -> error("no script step in this test") },
        )
        val bundle = bundle(
            "tv-control",
            """
            escape:
              steps:
                - id: nope
                  tool: RunSkillCommand
              returns: "${'$'}{nope}"
            """.trimIndent(),
        )

        val result = executor.execute(
            bundle = bundle,
            bundleHash = SkillBundleHasher.hash(bundle),
            commandName = "escape",
            inputs = emptyMap(),
            meta = meta(ToolInvokeSkill.NAME),
        )

        assertEquals(1, result.exitCode)
        assertTrue(!invoked)
        assertTrue(result.stderr.contains("cannot be called"))
    }

    @Test
    fun `a tool outside allowedTools is rejected without invoking it, even though it's not in RESTRICTED_TOOLS`() = runTest {
        var invoked = false
        val ungranted = FakeTool("device.mcp.call_tool") { invoked = true; "{}" }
        val executor = CompositeCommandExecutor(
            toolCatalog = catalog(ToolCategory.FILES to listOf(ungranted)),
            toolsFilter = TestToolsFilter(),
            runScript = { _, _, _, _ -> error("no script step in this test") },
        )
        val bundle = bundle(
            "tv-control",
            """
            locate:
              inputs: [device, target]
              steps:
                - id: screenshot
                  tool: device.mcp.call_tool
                  arguments:
                    name: get_screenshot
              returns: "${'$'}{screenshot}"
            """.trimIndent(),
        )

        val result = executor.execute(
            bundle = bundle,
            bundleHash = SkillBundleHasher.hash(bundle),
            commandName = "locate",
            inputs = mapOf("device" to "tv-1", "target" to "icon"),
            // Simulates a caller (e.g. a subagent) that was never granted device.mcp.call_tool.
            meta = meta("some.other.tool"),
        )

        assertEquals(1, result.exitCode)
        assertTrue(!invoked)
        assertTrue(result.stderr.contains("not among the tools available"))
    }

    @Test
    fun `an unavailable composite command name fails cleanly`() = runTest {
        val executor = CompositeCommandExecutor(
            toolCatalog = catalog(),
            toolsFilter = TestToolsFilter(),
            runScript = { _, _, _, _ -> error("no script step in this test") },
        )
        val bundle = bundle(
            "tv-control",
            """
            known:
              steps:
                - id: only
                  waitMs: 1
              returns: "${'$'}{only}"
            """.trimIndent(),
        )

        val result = executor.execute(
            bundle = bundle,
            bundleHash = SkillBundleHasher.hash(bundle),
            commandName = "missing",
            inputs = emptyMap(),
            meta = meta,
        )

        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("missing"))
    }

    @Test
    fun `an empty-string input is omitted from tool arguments instead of being sent as an empty target`() = runTest {
        var capturedArguments: Map<String, Any>? = null
        val tool = FakeTool("device.mcp.call_tool") { arguments -> capturedArguments = arguments; "{}" }
        val executor = CompositeCommandExecutor(
            toolCatalog = catalog(ToolCategory.FILES to listOf(tool)),
            toolsFilter = TestToolsFilter(),
            runScript = { _, _, _, _ -> error("no script step in this test") },
        )
        val bundle = bundle(
            "tv-control",
            """
            locate:
              inputs: [device]
              steps:
                - id: screenshot
                  tool: device.mcp.call_tool
                  arguments:
                    name: get_screenshot
                    target: "${'$'}{inputs.device}"
              returns: "${'$'}{screenshot}"
            """.trimIndent(),
        )

        executor.execute(
            bundle = bundle,
            bundleHash = SkillBundleHasher.hash(bundle),
            commandName = "locate",
            inputs = mapOf("device" to ""), // "current device" convention — see resolveValue's isOmittable
            meta = meta("device.mcp.call_tool"),
        )

        assertEquals(setOf("name"), capturedArguments!!.keys)
    }

    @Test
    fun `an unresolved reference fails with a descriptive message instead of throwing to the caller`() = runTest {
        val tool = FakeTool("device.mcp.call_tool") { "{}" }
        val executor = CompositeCommandExecutor(
            toolCatalog = catalog(ToolCategory.FILES to listOf(tool)),
            toolsFilter = TestToolsFilter(),
            runScript = { _, _, _, _ -> error("no script step in this test") },
        )
        val bundle = bundle(
            "tv-control",
            """
            typo:
              inputs: [target]
              steps:
                - id: only
                  tool: device.mcp.call_tool
                  arguments:
                    value: "${'$'}{inputs.target}"
              returns: "${'$'}{only}"
            """.trimIndent(),
        )

        val result = executor.execute(
            bundle = bundle,
            bundleHash = SkillBundleHasher.hash(bundle),
            commandName = "typo",
            inputs = emptyMap(), // declared input "target" is never supplied at call time
            meta = meta("device.mcp.call_tool"),
        )

        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("only"))
    }

    private class FakeTool(
        name: String,
        private val respond: (arguments: Map<String, Any>) -> String,
    ) : LLMToolSetup {
        override val fn = LLMRequest.Function(name = name, description = "fake $name")

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            invoke(functionCall, ToolInvocationMeta.localDefault())

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message =
            LLMRequest.Message(
                role = LLMMessageRole.function,
                content = respond(functionCall.arguments),
                name = functionCall.name,
            )
    }

    private class TestToolsFilter : AgentToolsFilter {
        override fun applyFilter(
            toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
        ): Map<ToolCategory, Map<String, LLMToolSetup>> = toolsByCategory
    }

    private fun catalog(
        vararg categories: Pair<ToolCategory, List<LLMToolSetup>>,
    ): AgentToolCatalog = object : AgentToolCatalog {
        override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = linkedMapOf(
            *categories.map { (category, tools) -> category to tools.associateBy { it.fn.name } }.toTypedArray()
        )
    }

    private fun bundle(skillId: String, commandsYaml: String): SkillBundle = SkillBundle.fromFiles(
        skillId = SkillId(skillId),
        files = listOf(
            SkillFile(
                normalizedPath = "SKILL.md",
                content = buildString {
                    appendLine("---")
                    appendLine("name: Name $skillId")
                    appendLine("description: Description $skillId")
                    appendLine("commands:")
                    commandsYaml.lines().forEach { line -> appendLine("  $line") }
                    appendLine("---")
                    append("body")
                }.toByteArray(),
            )
        ),
    )
}
