package ru.souz.backend.agent.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.bundle.SkillManifest
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidator
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.TestSkillRegistryRepository
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.agent.runtime.conversation.BackendMergedToolCatalog
import ru.souz.backend.testBackendClientSkills
import ru.souz.backend.testCoreTool
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.skills.registry.SkillStorageScope
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import ru.souz.tool.skills.ToolInvokeSkill
import ru.souz.tool.skills.ToolRunSkillCommand

class BackendSkillResolutionTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `request tool snapshot hides disabled compiled tools and keeps file skills`() = runTest {
        val enabledTool = RecordingTool("EnabledTool")
        val disabledTool = RecordingTool("DisabledTool")
        val catalog = catalog(enabledTool, disabledTool)
        val fileSkill = fileSkillBundle()
        val repository = SingleBundleRepository(fileSkill)
        val home = Files.createTempDirectory("backend-skill-tools-").also(createdPaths::add)
        val stateRoot = home.resolve("state").also(Files::createDirectories)
        createUserScopedBundleRoot(stateRoot, USER_ID, fileSkill)
        val sandbox = LocalRuntimeSandbox(
            scope = SandboxScope(userId = USER_ID),
            settingsProvider = TestSettingsProvider(),
            homePath = home,
            stateRoot = stateRoot,
        )
        val commandTool = ToolRunSkillCommand(
            sandboxResolver = ToolInvocationRuntimeSandboxResolver.fixed(sandbox),
            skillStorageScope = SkillStorageScope.USER_SCOPED,
        )
        val mutableEnabledTools = linkedSetOf("EnabledTool")
        val toolsFilter = BackendRequestToolsFilter(mutableEnabledTools)
        mutableEnabledTools += "DisabledTool"

        val approvalGate = approvingGate(repository)
        val getSkillsNamesByCategory = ToolGetSkillsNamesByCategory(
            toolCatalog = catalog,
            toolsFilter = toolsFilter,
        )
        val runtimeCommand = ToolInvokeSkill(
            toolCatalog = catalog,
            toolsFilter = toolsFilter,
            skillBundleProvider = repository,
            commandTool = commandTool,
            approvalGate = approvalGate,
        )
        val meta = ToolInvocationMeta(userId = USER_ID, conversationId = "conversation-a")
        val compiledNames = getSkillsNamesByCategory.invoke(
            LLMResponse.FunctionCall(
                name = "GetSkillsNamesByCategory",
                arguments = mapOf("category" to ToolCategory.FILES.name),
            ),
            meta,
        ).contentJson()
        val unknownCategoryNames = getSkillsNamesByCategory.invoke(
            LLMResponse.FunctionCall(
                name = "GetSkillsNamesByCategory",
                arguments = mapOf("category" to "UNKNOWN_CATEGORY"),
            ),
            meta,
        ).contentJson()

        assertEquals(listOf("EnabledTool"), compiledNames["skillNames"].map { it.asText() })
        assertEquals("category_not_found", unknownCategoryNames["error"]["code"].asText())
        assertTrue(unknownCategoryNames["category"].isNull)
        assertFalse(compiledNames.toString().contains("DisabledTool"))
        val enabledResult = runtimeCommand.invoke(
            skillCall("EnabledTool", mapOf("value" to "ok")),
            meta,
        )
        val disabledResult = runtimeCommand.invoke(
            skillCall("DisabledTool"),
            meta,
        ).contentJson()
        val fileResult = runtimeCommand.invoke(
            skillCall(
                FILE_SKILL_ID,
                mapOf(
                    "runtime" to SandboxCommandRuntime.BASH.name,
                    "script" to "printf 'file-skill-ok'",
                ),
            ),
            meta,
        ).contentJson()

        assertEquals("enabled-ok", enabledResult.content)
        assertEquals(mapOf("value" to "ok"), enabledTool.lastArguments)
        assertEquals(1, enabledTool.invocationCount)
        assertEquals("skill_disabled", disabledResult["error"]["code"].asText())
        assertEquals(0, fileResult["exitCode"].asInt())
        assertEquals("file-skill-ok", fileResult["stdout"].asText())
        assertEquals(0, disabledTool.invocationCount)
    }

    @Test
    fun `client Skills resolve through compiled adapters in the skill-resolution catalog`() = runTest {
        val clientSkills = testBackendClientSkills()
        val catalog = BackendMergedToolCatalog(
            primary = catalog(),
            additional = clientSkills,
        )
        val toolsFilter = BackendRequestToolsFilter(emptySet<String>() + clientSkills.skillIds)
        val getSkillByName = ToolGetSkillByName(
            toolCatalog = catalog,
            toolsFilter = toolsFilter,
            skillBundleProvider = clientSkills,
            legacyCommandTool = testCoreTool("RunSkillCommand"),
        )
        val getSkillsNamesByCategory = ToolGetSkillsNamesByCategory(
            toolCatalog = catalog,
            toolsFilter = toolsFilter,
        )
        val runtimeCommand = ToolInvokeSkill(
            toolCatalog = catalog,
            toolsFilter = toolsFilter,
            skillBundleProvider = clientSkills,
            commandTool = testCommandTool(),
        )
        val meta = ToolInvocationMeta(userId = USER_ID, conversationId = "conversation-a")

        val askDetail = getSkillByName.invoke(
            LLMResponse.FunctionCall(
                name = "GetSkillByName",
                arguments = mapOf("skillId" to "user.ask"),
            ),
            meta,
        ).contentJson()
        val chatNames = getSkillsNamesByCategory.invoke(
            LLMResponse.FunctionCall(
                name = "GetSkillsNamesByCategory",
                arguments = mapOf("category" to ToolCategory.CHAT.name),
            ),
            meta,
        ).contentJson()
        val applicationNames = getSkillsNamesByCategory.invoke(
            LLMResponse.FunctionCall(
                name = "GetSkillsNamesByCategory",
                arguments = mapOf("category" to ToolCategory.APPLICATIONS.name),
            ),
            meta,
        ).contentJson()

        assertEquals("user.ask", askDetail["skill"]["skillId"].asText())
        assertContains(askDetail["skill"]["description"].asText(), "# Ask the user")
        assertEquals(listOf("user.ask"), chatNames["skillNames"].map { it.asText() })
        assertEquals(listOf("device.media.open"), applicationNames["skillNames"].map { it.asText() })
        assertEquals("user.ask", runtimeCommand.delegatedToolName("user.ask"))
        assertEquals("device.media.open", runtimeCommand.delegatedToolName("device.media.open"))
    }

    @Test
    fun `enabled client adapters take precedence over same id user bundles`() = runTest {
        val clientSkills = testBackendClientSkills()
        val catalog = BackendMergedToolCatalog(
            primary = catalog(),
            additional = clientSkills,
        )
        val repository = SingleBundleRepository(
            skillBundle(
                skillId = "user.ask",
                name = "Shadow Ask",
                description = "A user replacement that must not be exposed.",
                body = "Use shadow instructions.",
            )
        )
        val toolsFilter = BackendRequestToolsFilter(clientSkills.skillIds)
        val getSkillByName = ToolGetSkillByName(
            toolCatalog = catalog,
            toolsFilter = toolsFilter,
            skillBundleProvider = repository,
            legacyCommandTool = testCoreTool("RunSkillCommand"),
            approvalGate = approvingGate(repository),
        )
        val runtimeCommand = ToolInvokeSkill(
            toolCatalog = catalog,
            toolsFilter = toolsFilter,
            skillBundleProvider = repository,
            commandTool = testCommandTool(),
            approvalGate = approvingGate(repository),
        )
        val meta = ToolInvocationMeta(userId = USER_ID, conversationId = "conversation-a")

        val askDetail = getSkillByName.invoke(
            LLMResponse.FunctionCall(
                name = "GetSkillByName",
                arguments = mapOf("skillId" to "user.ask"),
            ),
            meta,
        ).contentJson()
        val invocation = runtimeCommand.invoke(
            skillCall("user.ask", mapOf("question" to "Ready?")),
            meta,
        ).contentJson()

        val description = askDetail["skill"]["description"].asText()
        assertContains(description, "# Ask the user")
        assertFalse(description.contains("user replacement"))
        assertEquals("client_context_missing", invocation["error"]["code"].asText())
    }

    @Test
    fun `disabled client Skills are unavailable to generic skill invocation`() = runTest {
        val runtimeCommand = ToolInvokeSkill(
            toolCatalog = catalog(),
            toolsFilter = BackendRequestToolsFilter(emptySet()),
            skillBundleProvider = TestSkillRegistryRepository,
            commandTool = testCommandTool(),
        )
        val getSkillsNamesByCategory = ToolGetSkillsNamesByCategory(
            toolCatalog = catalog(),
            toolsFilter = BackendRequestToolsFilter(emptySet()),
        )
        val meta = ToolInvocationMeta(userId = USER_ID, conversationId = "conversation-a")

        val chatNames = getSkillsNamesByCategory.invoke(
            LLMResponse.FunctionCall(
                name = "GetSkillsNamesByCategory",
                arguments = mapOf("category" to ToolCategory.CHAT.name),
            ),
            meta,
        ).contentJson()
        val invocation = runtimeCommand.invoke(
            skillCall("user.ask"),
            meta,
        ).contentJson()

        assertEquals(emptyList(), chatNames["skillNames"].map { it.asText() })
        assertEquals("skill_not_found", invocation["error"]["code"].asText())
    }

    private fun createUserScopedBundleRoot(
        stateRoot: Path,
        userId: String,
        bundle: SkillBundle,
    ) {
        val encodedUserId = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(userId.toByteArray(StandardCharsets.UTF_8))
        Files.createDirectories(
            stateRoot.resolve("skills/users/$encodedUserId/skills/${bundle.skillId.value}/bundles/${SkillBundleHasher.hash(bundle)}")
        )
    }

    private fun skillCall(
        skillId: String,
        arguments: Map<String, Any> = emptyMap(),
    ): LLMResponse.FunctionCall = LLMResponse.FunctionCall(
        name = "RunSkillCommand",
        arguments = mapOf("skillId" to skillId, "arguments" to arguments),
    )

    private companion object {
        const val USER_ID = "backend-user"
        const val FILE_SKILL_ID = "file-skill"
    }
}

private class RecordingTool(name: String) : LLMToolSetup {
    var lastArguments: Map<String, Any> = emptyMap()
    var invocationCount: Int = 0

    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = name,
        description = "Description $name",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf("value" to LLMRequest.Property("string")),
        ),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        invocationCount += 1
        lastArguments = functionCall.arguments
        return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = "enabled-ok",
            name = functionCall.name,
        )
    }
}

private fun catalog(vararg tools: LLMToolSetup): AgentToolCatalog = object : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
        mapOf(ToolCategory.FILES to tools.associateBy { it.fn.name })
}

private fun fileSkillBundle(): SkillBundle {
    return skillBundle(
        skillId = "file-skill",
        name = "File Skill",
        description = "A user-installed file-backed skill.",
        body = "Use the file skill.",
    )
}

private fun skillBundle(
    skillId: String,
    name: String,
    description: String,
    body: String,
): SkillBundle {
    val manifest = SkillManifest(
        name = name,
        description = description,
        rawFrontmatter = "name: $name",
    )
    return SkillBundle(
        skillId = SkillId(skillId),
        manifest = manifest,
        files = listOf(SkillFile("SKILL.md", body.toByteArray())),
        skillMarkdownBody = body,
    )
}

private fun testCommandTool(): ToolRunSkillCommand =
    ToolRunSkillCommand(
        ToolInvocationRuntimeSandboxResolver {
            error("The test skill command sandbox is not configured.")
        }
    )

private fun approvingGate(repository: SkillRegistryRepository): SkillApprovalGate =
    SkillApprovalGate(
        validationStore = repository,
        llmValidator = SkillValidator { emptyList() },
    )

private class SingleBundleRepository(
    private val bundle: SkillBundle,
) : SkillRegistryRepository {
    private val stored = StoredSkill(
        userId = "backend-user",
        skillId = bundle.skillId,
        manifest = bundle.manifest,
        bundleHash = SkillBundleHasher.hash(bundle),
        createdAt = Instant.EPOCH,
    )

    override suspend fun listSkills(userId: String): List<StoredSkill> = listOf(stored)

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? =
        stored.takeIf { it.skillId == skillId }

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? =
        stored.takeIf { it.manifest.name == name }

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill = stored

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? =
        bundle.takeIf { it.skillId == skillId }

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = null

    override suspend fun saveValidation(record: SkillValidationRecord) = Unit
}

private fun LLMRequest.Message.contentJson() = restJsonMapper.readTree(content)
