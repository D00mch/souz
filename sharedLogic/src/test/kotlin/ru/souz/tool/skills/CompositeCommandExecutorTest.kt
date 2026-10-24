package ru.souz.tool.skills

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompositeCommandExecutorTest {
    private val meta = ToolInvocationMeta("owner", "conversation", "request", attributes = mapOf("clientToolSessionId" to "client"))
    private val scripts = mockk<SkillCommandExecutor>()

    @Test
    fun `file references and prepared paths pass between composite tools and remain structured results`() = runTest {
        val file = ru.souz.llms.FileContent(ru.souz.llms.FileReference("file-id"), "report.md", "text/markdown", 12)
        val path = "/workspace/report.md"
        val access = mockk<ru.souz.agent.spi.AgentAttachmentAccess> {
            coEvery { prepare(meta, file.file.fileId) } returns ru.souz.agent.spi.PreparedAttachment(file, path)
            coEvery { register(meta, path, null, file.presentation) } returns file
            coEvery { register(meta, null, file.file.fileId, file.presentation) } returns file
        }
        val command = bundle("""
            inputs: [fileId]
            steps:
              - id: prepared
                tool: PrepareAttachment
                arguments: {fileId: '${'$'}{inputs.fileId}'}
              - id: saved
                tool: AttachFileToReply
                arguments: {path: '${'$'}{prepared.path}'}
              - id: attached
                tool: AttachFileToReply
                arguments: {fileId: '${'$'}{saved.file.fileId}'}
            returns: '${'$'}{attached}'
        """)
        val tools = ru.souz.tool.files.AttachmentTools(access).tools
        val result = runner(command, tools).invoke(LLMResponse.FunctionCall(ToolInvokeSkill.NAME, mapOf(
            "skillId" to "skill", "arguments" to mapOf("composite" to "run", "inputs" to mapOf("fileId" to "file-id")),
        )), meta)
        assertEquals(listOf(file), result.files)
        assertEquals(listOf(file), result.replyFiles)
        val payload = restJsonMapper.readValue(result.content, SandboxCommandResult::class.java)
        assertEquals(0, payload.exitCode)
        assertEquals(file.file.fileId, restJsonMapper.readTree(payload.stdout)["file"]["fileId"].asText())
        coVerify(exactly = 1) { access.register(meta, path, null, file.presentation) }
        coVerify(exactly = 1) { access.register(meta, null, file.file.fileId, file.presentation) }
        val invalid = tools.last().invoke(LLMResponse.FunctionCall("AttachFileToReply", mapOf("path" to path, "fileId" to "file-id")), meta)
        assertTrue(restJsonMapper.readTree(invalid.content).has("error"))
        assertTrue(invalid.replyFiles.isEmpty())
        coEvery { access.prepare(any(), any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> {
            tools.first().invoke(LLMResponse.FunctionCall("PrepareAttachment", mapOf("fileId" to "file-id")), meta)
        }
    }

    @Test
    fun `discovery and mixed execution preserve values identity and remaining deadline`() = runBlocking {
        val text = "42 inch TV"
        val bundle = bundle("""
            inputs: [target, options, empty]
            steps:
              - id: read
                tool: device.read
                arguments: {target: '${'$'}{inputs.target}', options: '${'$'}{inputs.options}', omit: '${'$'}{inputs.empty}'}
              - id: settled
                waitMs: 100
              - id: body
                script: scripts/body.py
                runtime: PYTHON
                args: ['uri=${'$'}{read.content[0].uri}']
              - id: sent
                tool: device.send
                arguments: {body: '${'$'}{body}', flags: ['${'$'}{read.ok}', 3]}
            returns: '${'$'}{sent}: ${'$'}{read.content[0]}'
        """)
        val read = tool("device.read") { call, identity ->
            assertEquals(meta, identity)
            assertEquals(mapOf("target" to text, "options" to mapOf("x" to 7)), call.arguments)
            """{"content":[{"uri":"screen.png"}],"ok":true}"""
        }
        val send = tool("device.send") { call, identity ->
            assertEquals(meta, identity)
            assertEquals(mapOf("body" to "{\"device\":1}\n", "flags" to listOf(true, 3)), call.arguments)
            text
        }
        coEvery { scripts.execute(bundle, any(), any(), meta) } coAnswers {
            val args = thirdArg<SkillCommandExecutor.Args>()
            assertEquals(SandboxCommandRuntime.PYTHON, args.runtime)
            assertEquals("scripts/body.py", args.scriptPath)
            assertEquals(listOf("uri=screen.png"), args.args)
            assertTrue(args.timeoutMillis in 1..4_900)
            SandboxCommandResult(0, "{\"device\":1}\n", "")
        }
        val catalog = AgentToolCatalog(mapOf(ToolCategory.FILES to listOf(read, send)))
        val provider = mockk<SkillBundleProvider> { coEvery { loadSkillBundle(meta.userId, bundle.skillId) } returns bundle }
        val detail = ToolGetSkillByName({ catalog }, RuntimePassThroughToolsFilter, provider)
            .invoke(LLMResponse.FunctionCall(ToolGetSkillByName.NAME, mapOf("skillId" to "skill")), meta)
        assertEquals(listOf("target", "options", "empty"), restJsonMapper.readTree(detail.content)["skill"]["commands"]["run"]["inputs"].map { it.asText() })
        val result = runner(bundle, listOf(read, send)).run(inputs = mapOf("target" to text, "options" to "{\"x\":7}", "empty" to ""), timeout = 5_000)
        assertEquals(SandboxCommandResult(0, "$text: {\"uri\":\"screen.png\"}", ""), result)
    }

    @Test
    fun `composite forwards its last tool image and drops it after a later failure or imageless result`() = runTest {
        val screen = tool("screen") { _, _ -> "frame" }
        coEvery { screen.invoke(any(), meta) } returns LLMRequest.Message(
            LLMMessageRole.function, "frame", name = "screen", attachments = listOf("https://screen.test/fresh.webp"),
        )
        val plain = tool("plain") { _, _ -> "capture unavailable" }
        val fail = tool("fail") { _, _ -> error("offline") }
        for ((last, expected) in listOf("screen" to listOf("https://screen.test/fresh.webp"), "plain" to null, "fail" to null)) {
            val command = bundle("steps: [{id: first, tool: screen}, {id: last, tool: $last}]\nreturns: '\${last}'")
            val result = runner(command, listOf(screen, plain, fail)).invoke(LLMResponse.FunctionCall(ToolInvokeSkill.NAME, mapOf(
                "skillId" to "skill", "arguments" to mapOf("composite" to "run"),
            )), meta)
            assertEquals(expected, result.attachments)
            assertTrue(!result.content.contains("attachments"))
        }
    }

    @Test
    fun `invocations use current filtered tools and reject unavailable steps before effects`() = runTest {
        val first = tool("first") { _, _ -> "done" }
        val last = tool("last") { _, _ -> "done" }
        var enabled = true
        val filter = mockk<AgentToolsFilter> { every { applyFilter(any()) } answers { if (enabled) firstArg() else emptyMap() } }
        val runner = runner(bundle("steps: [{id: a, tool: first}, {id: b, tool: last}]\nreturns: '\${b}'"), listOf(first, last), filter)
        assertEquals(0, runner.run().exitCode)
        enabled = false
        assertEquals(1, runner.run().exitCode)
        coVerify(exactly = 1) { first.invoke(any(), meta) }
        coVerify(exactly = 1) { last.invoke(any(), meta) }
        for (name in NON_DELEGABLE_SKILL_TOOLS) {
            val denied = runner(bundle("steps: [{id: a, tool: first}, {id: b, tool: $name}]\nreturns: '\${b}'"), listOf(first, tool(name) { _, _ -> error("must not run") }))
            assertContains(denied.run().stderr, name)
        }
        val missing = runner(bundle("steps: [{id: a, tool: first}, {id: b, tool: missing}]\nreturns: '\${b}'"), listOf(first))
        assertContains(missing.run().stderr, "missing")
        coVerify(exactly = 1) { first.invoke(any(), meta) }
    }

    @Test
    fun `script and tool failures stop execution and cancellation remains exceptional`() = runTest {
        val later = tool("later") { _, _ -> error("must not run") }
        val bundle = bundle("steps: [{id: script, script: scripts/run.sh, runtime: BASH}, {id: later, tool: later}]\nreturns: '\${later}'")
        for (failure in listOf(SandboxCommandResult(2, "partial", "failed"), SandboxCommandResult(-1, "", "timeout", true))) {
            coEvery { scripts.execute(any(), any(), any(), any()) } returns failure
            val result = runner(bundle, listOf(later)).run()
            assertEquals(failure.exitCode, result.exitCode)
            assertEquals(failure.timedOut, result.timedOut)
            assertContains(result.stderr, "script")
        }
        coEvery { scripts.execute(any(), any(), any(), any()) } throws CancellationException("cancel script")
        assertFailsWith<CancellationException> { runner(bundle, listOf(later)).run() }
        val toolBundle = bundle("steps: [{id: fail, tool: fail}, {id: later, tool: later}]\nreturns: '\${later}'")
        assertContains(runner(toolBundle, listOf(tool("fail") { _, _ -> error("offline") }, later)).run().stderr, "offline")
        assertFailsWith<CancellationException> {
            runner(toolBundle, listOf(tool("fail") { _, _ -> throw CancellationException("cancel tool") }, later)).run()
        }
        coVerify(exactly = 0) { later.invoke(any(), any()) }
    }

    @Test
    fun `missing inputs paths and commands fail and waits and tools obey total timeout`() = runTest {
        val wait = runner(bundle("inputs: [required]\nsteps: [{id: wait, waitMs: 100}]\nreturns: '\${wait}'"))
        assertContains(wait.run().stderr, "Missing inputs")
        assertEquals(1, wait.run(command = "missing").exitCode)
        assertTrue(wait.run(inputs = mapOf("required" to "x"), timeout = 10).timedOut)
        val read = tool("read") { _, _ -> "{\"items\":[]}" }
        for (reference in listOf("\${read.items[0]}", "\${read.absent}")) {
            assertContains(runner(bundle("steps: [{id: read, tool: read}]\nreturns: '$reference'"), listOf(read)).run().stderr, "Unresolved reference")
        }
        val blocked = runner(bundle("steps: [{id: blocked, tool: blocked}]\nreturns: '\${blocked}'"), listOf(tool("blocked") { _, _ -> awaitCancellation() }))
        assertTrue(blocked.run(timeout = 10).timedOut)
    }

    private fun bundle(command: String) = SkillBundle.fromFiles(SkillId("skill"), listOf(SkillFile(
        "SKILL.md", ("---\nname: skill\ndescription: Test\ncommands:\n  run:\n" + command.trimIndent().prependIndent("    ") + "\n---\nUse run.").toByteArray(),
    )))

    private fun runner(bundle: SkillBundle, tools: List<LLMToolSetup> = emptyList(), filter: AgentToolsFilter = RuntimePassThroughToolsFilter) = ToolInvokeSkill(
        { AgentToolCatalog(mapOf(ToolCategory.FILES to tools)) }, filter, { _, _ -> bundle }, scripts,
    )

    private suspend fun ToolInvokeSkill.run(inputs: Map<String, String> = emptyMap(), command: String = "run", timeout: Long = 60_000): SandboxCommandResult =
        restJsonMapper.readValue(invoke(LLMResponse.FunctionCall(ToolInvokeSkill.NAME, mapOf(
            "skillId" to "skill", "arguments" to mapOf("composite" to command, "inputs" to inputs, "timeoutMillis" to timeout),
        )), meta).content, SandboxCommandResult::class.java)

    private fun tool(name: String, action: suspend (LLMResponse.FunctionCall, ToolInvocationMeta) -> String): LLMToolSetup = mockk<LLMToolSetup> {
        every { fn } returns LLMRequest.Function(name, name, LLMRequest.Parameters("object"))
        coEvery { this@mockk.invoke(any(), any()) } coAnswers {
            LLMRequest.Message(LLMMessageRole.function, action(firstArg(), secondArg()), name = name)
        }
    }
}
