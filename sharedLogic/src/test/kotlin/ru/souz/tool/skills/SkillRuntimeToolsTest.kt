package ru.souz.tool.skills

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.SkillToolBindingTags
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.giga.toGiga
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.tool.ToolCategory
import ru.souz.tool.portableSkillToolsDiModule
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillRuntimeToolsTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `summaries are metadata only and enabled compiled tools win collisions`() = runTest {
        val repository = repository(bundle("alpha"), bundle("disabled"), bundle("collision"))
        val zeta = RecordingTool("zeta")
        val disabled = RecordingTool("disabled")
        val collision = RecordingTool("collision", description = "compiled collision")
        val catalog = catalog(
            ToolCategory.FILES to listOf(zeta, disabled),
            ToolCategory.WEB_SEARCH to listOf(collision),
        )
        val filter = TestToolsFilter { tools ->
            tools.mapValues { (_, categoryTools) ->
                categoryTools
                    .filterKeys { it != "disabled" }
                    .mapValues { (name, tool) ->
                        if (name == "zeta") tool.withFunction { copy(description = "filtered zeta") } else tool
                    }
            }
        }

        val response = getSkillsTool(repository, catalog, filter).call(emptyMap())

        assertEquals(
            listOf("alpha", "collision", "disabled", "zeta"),
            response["results"].map { it["skillId"].asText() },
        )
        assertEquals(
            "compiled collision",
            response["results"].first { it["skillId"].asText() == "collision" }["description"].asText(),
        )
        assertEquals(
            "Description disabled",
            response["results"].first { it["skillId"].asText() == "disabled" }["description"].asText(),
        )
        assertEquals(
            "filtered zeta",
            response["results"].first { it["skillId"].asText() == "zeta" }["description"].asText(),
        )
        assertTrue(response["errors"].isEmpty)
        coVerify(exactly = 1) { repository.listSkills(USER_ID) }
        coVerify(exactly = 0) { repository.loadSkillBundle(any(), any()) }
    }

    @Test
    fun `batch details preserve order and return independent schemas and errors`() = runTest {
        val repository = repository(
            bundle(
                skillId = "bundle-skill",
                body = "Use the complete bundle instructions.",
                supportingFiles = mapOf("scripts/helper.sh" to "supporting-secret-content"),
            ),
            bundle("disabled"),
            bundle("collision", body = "This bundle must be shadowed."),
        )
        val compiled = RecordingTool("compiled")
        val disabled = RecordingTool("disabled")
        val collision = RecordingTool("collision", description = "compiled collision")
        val filteredCompiled = compiled.withFunction {
            copy(
                description = "filtered compiled",
                parameters = LLMRequest.Parameters(
                    type = "object",
                    properties = mapOf("filteredArgument" to LLMRequest.Property("string")),
                    required = listOf("filteredArgument"),
                ),
                fewShotExamples = listOf(
                    LLMRequest.FewShotExample("filtered example", mapOf("filteredArgument" to "ok"))
                ),
            )
        }
        val catalog = catalog(
            ToolCategory.FILES to listOf(compiled, disabled),
            ToolCategory.WEB_SEARCH to listOf(collision),
        )
        val filter = TestToolsFilter { tools ->
            tools.mapValues { (_, categoryTools) ->
                categoryTools
                    .filterKeys { it != "disabled" }
                    .mapValues { (name, tool) -> if (name == "compiled") filteredCompiled else tool }
            }
        }

        val response = getSkillsTool(repository, catalog, filter).call(
            mapOf(
                "skillIds" to listOf(
                    " compiled ",
                    "bundle-skill",
                    "compiled",
                    "",
                    "missing",
                    "disabled",
                    "collision",
                )
            )
        )

        assertEquals(
            listOf("compiled", "bundle-skill", "disabled", "collision"),
            response["results"].map { it["skillId"].asText() },
        )
        val toolDetail = response["results"][0]
        assertEquals("filtered compiled", toolDetail["description"].asText())
        assertTrue(toolDetail["inputSchema"]["properties"].has("filteredArgument"))
        assertEquals("filtered example", toolDetail["fewShotExamples"].single()["request"].asText())

        val bundleDetail = response["results"][1]
        assertEquals("Use the complete bundle instructions.", bundleDetail["skillMarkdownBody"].asText())
        assertEquals(listOf("scripts/helper.sh"), bundleDetail["supportingFiles"].map { it.asText() })
        assertFalse(bundleDetail.toString().contains("supporting-secret-content"))
        assertFalse(bundleDetail["inputSchema"]["properties"].has("skillId"))
        assertFalse(bundleDetail["inputSchema"]["properties"].has("activeSkills"))
        assertTrue(bundleDetail["inputSchema"]["properties"].has("runtime"))
        assertTrue(bundleDetail["returnSchema"]["properties"].has("stdout"))

        assertEquals("Description disabled", response["results"][2]["description"].asText())
        assertEquals("compiled collision", response["results"][3]["description"].asText())
        assertEquals(listOf("", "missing"), response["errors"].map { it["skillId"].asText() })
        assertEquals(
            listOf("invalid_skill_id", "skill_not_found"),
            response["errors"].map { it["code"].asText() },
        )
        coVerify(exactly = 0) { repository.listSkills(any()) }
        coVerify(exactly = 0) { repository.loadSkillBundle(any(), SkillId("collision")) }
    }

    @Test
    fun `discovery and invocation propagate cancellation`() = runTest {
        val repository = mockk<SkillRegistryRepository>()
        coEvery { repository.loadSkillBundle(any(), any()) } throws CancellationException("stop")

        assertFailsWith<CancellationException> {
            getSkillsTool(repository).call(mapOf("skillIds" to listOf("cancelled")))
        }
        assertFailsWith<CancellationException> {
            invokeSkillTool(repository).call(mapOf("skillId" to "cancelled"))
        }
    }

    @Test
    fun `compiled invocation forwards calls wins collisions and returns lookup errors`() = runTest {
        val repository = repository(bundle("compiled"))
        val compiled = RecordingTool("compiled", attachments = listOf("attachment-id"))
        val disabled = RecordingTool("disabled")
        val catalog = catalog(ToolCategory.FILES to listOf(compiled, disabled))
        val filter = TestToolsFilter { tools ->
            tools.mapValues { (_, categoryTools) -> categoryTools.filterKeys { it != "disabled" } }
        }
        val runner = invokeSkillTool(repository, catalog, filter)
        val meta = ToolInvocationMeta(userId = "runtime-user", conversationId = "conversation")
        val arguments = mapOf<String, Any>("value" to 7, "nested" to mapOf("ok" to true))

        val result = runner.invoke(
            LLMResponse.FunctionCall(
                name = ToolInvokeSkill.NAME,
                arguments = mapOf("skillId" to "compiled", "arguments" to arguments),
            ),
            meta,
        )

        assertEquals(arguments, compiled.lastArguments)
        assertEquals(meta, compiled.lastMeta)
        assertEquals("delegated-content", result.content)
        assertEquals(listOf("attachment-id"), result.attachments)
        assertEquals(ToolInvokeSkill.NAME, result.name)
        coVerify(exactly = 0) { repository.loadSkillBundle(any(), SkillId("compiled")) }
        assertEquals("invalid_skill_id", runner.call(mapOf("skillId" to " "))["error"]["code"].asText())
        assertEquals("skill_disabled", runner.call(mapOf("skillId" to "disabled"))["error"]["code"].asText())
        assertEquals("skill_not_found", runner.call(mapOf("skillId" to "missing"))["error"]["code"].asText())
    }

    @Test
    fun `file backed invocation binds authorization and preserves complete output`() = runTest {
        val home = createTempDirectory("future-skill-home-")
        val stateRoot = home.resolve("state").createDirectories()
        stateRoot.resolve("skills/file-skill").createDirectories()
        val commandTool = ToolRunSkillCommand(
            ToolInvocationRuntimeSandboxResolver.fixed(localSandbox(home, stateRoot))
        )
        val fileSkill = bundle("file-skill")
        val repository = repository(fileSkill)
        val runner = ToolInvokeSkill(
            toolCatalog = catalog(),
            toolsFilter = TestToolsFilter(),
            repository = repository,
            commandTool = commandTool,
        )
        val largeOutput = "x".repeat(25_050)
        val arguments = mapOf<String, Any>(
            "skillId" to "nested-wrong-id",
            "activeSkills" to listOf(mapOf("skillId" to "nested-active")),
            "runtime" to SandboxCommandRuntime.BASH.name,
            "script" to "printf \"\$SOUZ_SKILL_ID:\"; printf '$largeOutput'",
            "timeoutMillis" to 5_000,
        )

        val genericResult = runner.call(
            mapOf("skillId" to "file-skill", "arguments" to arguments),
            ToolInvocationMeta(userId = USER_ID),
        )

        assertEquals(0, genericResult["exitCode"].asInt())
        assertTrue(genericResult["stdout"].asText().startsWith("file-skill:"))
        assertEquals("file-skill:".length + largeOutput.length, genericResult["stdout"].asText().length)
        assertFalse(genericResult["stdout"].asText().contains("truncated"))
        coVerify(exactly = 1) { repository.loadSkillBundle(USER_ID, fileSkill.skillId) }

        val legacyResult = commandTool.suspendInvoke(
            ToolRunSkillCommand.Input(
                skillId = "file-skill",
                runtime = SandboxCommandRuntime.BASH,
                script = "printf '$largeOutput'",
                timeoutMillis = 5_000,
                activeSkills = listOf(
                    ToolRunSkillCommand.ActiveSkillInput(
                        skillId = "file-skill",
                        bundleHash = SkillBundleHasher.hash(fileSkill),
                    )
                ),
            ),
            ToolInvocationMeta(userId = USER_ID),
        )
        assertContains(legacyResult, "...[truncated")
    }

    @Test
    fun `portable composition exposes tagged runtime tools outside the catalog`() {
        val home = createTempDirectory("skill-di-home-")
        val stateRoot = home.resolve("state").createDirectories()
        val repository = repository()
        val catalog = catalog(ToolCategory.FILES to listOf(RecordingTool("ordinary")))
        val direct = DI.direct {
            bindSingleton<ToolInvocationRuntimeSandboxResolver> {
                ToolInvocationRuntimeSandboxResolver.fixed(localSandbox(home, stateRoot))
            }
            bindSingleton<SkillRegistryRepository> { repository }
            bindSingleton<AgentToolCatalog> { catalog }
            bindSingleton<AgentToolsFilter> { TestToolsFilter() }
            import(portableSkillToolsDiModule())
        }

        val legacy = direct.instance<LLMToolSetup>(tag = SkillToolBindingTags.COMMAND_TOOL)
        val getSkills = direct.instance<LLMToolSetup>(tag = SkillToolBindingTags.GET_SKILLS_TOOL)
        val runtimeCommand = direct.instance<LLMToolSetup>(tag = SkillToolBindingTags.RUNTIME_COMMAND_TOOL)

        assertEquals(ToolRunSkillCommand.NAME, legacy.fn.name)
        assertEquals(ToolGetSkills.NAME, getSkills.fn.name)
        assertEquals(ToolInvokeSkill.NAME, runtimeCommand.fn.name)
        assertFalse(catalog.toolsByCategory.values.any { ToolGetSkills.NAME in it || ToolInvokeSkill.NAME in it })
    }

    private fun getSkillsTool(
        repository: SkillRegistryRepository,
        catalog: AgentToolCatalog = catalog(),
        filter: AgentToolsFilter = TestToolsFilter(),
    ): ToolGetSkills = ToolGetSkills(
        toolCatalog = catalog,
        toolsFilter = filter,
        repository = repository,
        legacyCommandTool = ToolRunSkillCommand(mockk(relaxed = true)).toGiga(),
    )

    private fun invokeSkillTool(
        repository: SkillRegistryRepository,
        catalog: AgentToolCatalog = catalog(),
        filter: AgentToolsFilter = TestToolsFilter(),
    ): ToolInvokeSkill = ToolInvokeSkill(
        toolCatalog = catalog,
        toolsFilter = filter,
        repository = repository,
        commandTool = ToolRunSkillCommand(mockk(relaxed = true)),
    )

    private fun localSandbox(home: Path, stateRoot: Path): LocalRuntimeSandbox {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        return LocalRuntimeSandbox(
            scope = SandboxScope(userId = USER_ID),
            settingsProvider = settingsProvider,
            homePath = home,
            stateRoot = stateRoot,
        )
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)

    private companion object {
        const val USER_ID = "user-1"
    }
}

private class RecordingTool(
    name: String,
    description: String = "description for $name",
    private val attachments: List<String> = emptyList(),
) : LLMToolSetup {
    override val fn = LLMRequest.Function(
        name = name,
        description = description,
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf("value" to LLMRequest.Property("number", "A value.")),
        ),
        fewShotExamples = listOf(LLMRequest.FewShotExample("base example", mapOf("value" to 1))),
        returnParameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf("result" to LLMRequest.Property("string")),
        ),
    )
    var lastArguments: Map<String, Any>? = null
    var lastMeta: ToolInvocationMeta? = null

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        lastArguments = functionCall.arguments
        lastMeta = meta
        return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = "delegated-content",
            attachments = attachments,
            name = functionCall.name,
        )
    }
}

private fun LLMToolSetup.withFunction(
    transform: LLMRequest.Function.() -> LLMRequest.Function,
): LLMToolSetup = object : LLMToolSetup by this {
    override val fn: LLMRequest.Function = this@withFunction.fn.transform()
}

private class TestToolsFilter(
    private val transform: (Map<ToolCategory, Map<String, LLMToolSetup>>) -> Map<ToolCategory, Map<String, LLMToolSetup>> = { it },
) : AgentToolsFilter {
    override fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
    ): Map<ToolCategory, Map<String, LLMToolSetup>> = transform(toolsByCategory)
}

private fun catalog(
    vararg categories: Pair<ToolCategory, List<LLMToolSetup>>,
): AgentToolCatalog = object : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = linkedMapOf(
        *categories.map { (category, tools) -> category to tools.associateBy { it.fn.name } }.toTypedArray()
    )
}

private fun repository(vararg bundles: SkillBundle): SkillRegistryRepository {
    val bundlesById = bundles.associateBy { it.skillId.value }
    return mockk {
        coEvery { listSkills(any()) } answers {
            val userId = firstArg<String>()
            bundles.map { it.toStoredSkill(userId) }
        }
        coEvery { loadSkillBundle(any(), any()) } answers {
            bundlesById[secondArg<String>()]
        }
    }
}

private fun SkillBundle.toStoredSkill(userId: String): StoredSkill = StoredSkill(
    userId = userId,
    skillId = skillId,
    manifest = manifest,
    bundleHash = SkillBundleHasher.hash(this),
    createdAt = Instant.EPOCH,
)

private suspend fun LLMToolSetup.call(
    arguments: Map<String, Any>,
    meta: ToolInvocationMeta = ToolInvocationMeta(userId = "user-1"),
) = restJsonMapper.readTree(
    invoke(LLMResponse.FunctionCall(fn.name, arguments), meta).content
)

private fun bundle(
    skillId: String,
    body: String = "Follow these instructions.",
    supportingFiles: Map<String, String> = emptyMap(),
): SkillBundle = SkillBundle.fromFiles(
    skillId = SkillId(skillId),
    files = buildList {
        add(
            SkillFile(
                normalizedPath = "SKILL.md",
                content = """
                    ---
                    name: Name $skillId
                    description: Description $skillId
                    author: Test Author
                    version: 1.0
                    ---
                    $body
                """.trimIndent().toByteArray(),
            )
        )
        supportingFiles.forEach { (path, fileContent) ->
            add(SkillFile(path, fileContent.toByteArray()))
        }
    },
)
