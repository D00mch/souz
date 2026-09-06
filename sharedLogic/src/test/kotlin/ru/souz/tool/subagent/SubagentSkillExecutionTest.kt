package ru.souz.tool.subagent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.SubagentRunner
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.state.AgentTools
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.skills.registry.FileSystemSkillRegistryRepository
import ru.souz.tool.immutableToolCatalogSnapshot
import ru.souz.tool.skills.SkillCommandExecutor
import ru.souz.tool.skills.ToolInvokeSkill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubagentSkillExecutionTest {
    @Test
    fun `child uses existing loose scripts and persistent outputs without approving again`() = runTest {
        val home = Files.createTempDirectory("subagent-skill-")
        try {
            val runtime = LocalRuntimeSandbox(
                scope = SandboxScope("owner"),
                settingsProvider = mockk<SettingsProvider> { every { forbiddenFolders } returns emptyList() },
                homePath = home,
                stateRoot = home.resolve("state"),
            )
            val fileSystem = runtime.fileSystem
            val root = "${runtime.runtimePaths.skillsDirPath}/loose"
            fileSystem.writeText(fileSystem.resolvePath("$root/SKILL.md"), "---\nname: Loose\ndescription: Test\n---\nRun run.sh")
            fileSystem.writeText(fileSystem.resolvePath("$root/run.sh"), "#!/bin/sh\nprintf original")
            assertTrue(java.io.File("$root/run.sh").setExecutable(true))
            val registry = FileSystemSkillRegistryRepository(runtime)
            val commands = SkillCommandExecutor(ToolInvocationRuntimeSandboxResolver.fixed(runtime))
            val catalog = immutableToolCatalogSnapshot(emptyMap())
            val filter = mockk<AgentToolsFilter> { every { applyFilter(any()) } answers { firstArg() } }
            val approval = mockk<SkillApprovalGate> {
                coEvery { ensureApproved(any()) } answers {
                    val bundle = firstArg<SkillApprovalGate.Input>().bundle
                    SkillApprovalGate.Result.Approved(bundle, SkillBundleHasher.hash(bundle), null)
                }
            }
            var childTools = emptyList<LLMToolSetup>()
            val runner = mockk<SubagentRunner> {
                coEvery { execute(any(), any(), any(), any(), any(), any()) } answers {
                    childTools = thirdArg()
                    AgentExecutionResult("ready", mockk())
                }
            }
            val meta = ToolInvocationMeta("owner", "conversation", attributes = mapOf("client" to "session"))
            val spawn = SubagentToolFactory(
                runner, mockk<AgentSettingsProvider>(), catalog, filter, registry, commands, approval,
            ).create(AgentSettings(LLMModel.Max.alias, 0.5f, AgentTools(emptyMap())))
            spawn.invoke(LLMResponse.FunctionCall(spawn.fn.name, mapOf("task" to "Run the skill", "skillIds" to listOf("loose"))), meta)

            fileSystem.writeText(fileSystem.resolvePath("$root/run.sh"), "#!/bin/sh\nprintf edited > report.txt\ncat report.txt")
            val arguments = mapOf<String, Any>(
                "skillId" to "loose",
                "arguments" to mapOf("runtime" to "PROCESS", "command" to listOf("./run.sh")),
            )
            val childResult = childTools.single().invoke(LLMResponse.FunctionCall(ToolInvokeSkill.NAME, arguments), meta)
            assertEquals("edited", restJsonMapper.readTree(childResult.content)["stdout"].asText())

            val readReport = LLMResponse.FunctionCall(ToolInvokeSkill.NAME, mapOf(
                "skillId" to "loose", "arguments" to mapOf("script" to "cat report.txt"),
            ))
            val nextResult = childTools.single().invoke(readReport, meta)
            assertEquals("edited", restJsonMapper.readTree(nextResult.content)["stdout"].asText())
            coVerify(exactly = 1) { approval.ensureApproved(any()) }

            val generic = ToolInvokeSkill(catalog, filter, registry, commands, approval)
            val genericResult = generic.invoke(readReport, meta)
            assertEquals("edited", restJsonMapper.readTree(genericResult.content)["stdout"].asText())
            assertEquals("edited", fileSystem.readText(fileSystem.resolveExistingFile("$root/report.txt")))
            assertFalse(fileSystem.resolvePath("$root/bundles").exists)
            assertFalse(fileSystem.resolvePath("$root/stored-skill.json").exists)
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
