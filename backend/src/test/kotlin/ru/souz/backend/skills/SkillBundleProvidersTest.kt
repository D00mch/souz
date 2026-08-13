package ru.souz.backend.skills

import java.io.InputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.client.BackendClientSkills
import ru.souz.backend.http.routeTestContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.ToolGetSkillByName

class SkillBundleProvidersTest {
    @Test
    fun `client catalog exposes its bundled definitions as immutable Skill bundles`() = runTest {
        val provider = clientSkills(
            mapOf(
                "skills/client/index.txt" to "indexed\n".toByteArray(),
                "skills/client/indexed/SKILL.md" to skillMarkdown(
                    name = "resource",
                    description = "resource description",
                    canonicalId = "canonical.resource",
                ),
            )
        )

        val stored = provider.listSkills(USER).single()
        val loaded = assertNotNull(provider.loadSkillBundle(USER, SkillId("canonical.resource")))

        assertEquals(SkillId("canonical.resource"), stored.skillId)
        assertEquals(SkillBundleHasher.hash(loaded), stored.bundleHash)
        loaded.skillMarkdownFile.content[0] = 'x'.code.toByte()
        assertEquals(
            '-',
            assertNotNull(provider.loadSkillBundle(USER, loaded.skillId))
                .skillMarkdownFile.content.first().toInt().toChar(),
        )
        assertNull(provider.loadSkillBundle(USER, SkillId("indexed")))
    }

    @Test
    fun `resource Skills win user collisions and tools win resource collisions`() = runTest {
        val resources = clientSkills(
            mapOf(
                "skills/client/index.txt" to "collision\n".toByteArray(),
                "skills/client/collision/SKILL.md" to skillMarkdown(
                    name = "resource collision",
                    description = "resource description",
                    canonicalId = "collision",
                ),
            )
        )
        val users = InMemoryProvider(bundle("collision", "user"))
        val composite = BackendSkillBundleProvider(resources, users)

        val selected = assertNotNull(composite.loadSkillBundle(USER, SkillId("collision")))
        assertEquals("resource description", selected.manifest.description)
        assertEquals(listOf(SkillId("collision")), composite.listSkillInventoryIds(USER))
        assertEquals("resource description", composite.listSkills(USER).single().manifest.description)

        val lookup = ToolGetSkillByName(
            toolCatalog = TestCatalog(
                mapOf(ToolCategory.CHAT to mapOf("collision" to DescriptionTool("tool description")))
            ),
            toolsFilter = RuntimePassThroughToolsFilter,
            skillBundleProvider = composite,
        )
        val response = lookup.invoke(
            LLMResponse.FunctionCall(
                name = ToolGetSkillByName.NAME,
                arguments = mapOf("skillId" to "collision"),
            ),
            ToolInvocationMeta(userId = USER),
        )
        val json = restJsonMapper.readTree(response.content)

        assertEquals("tool description", json["skill"]["description"].asText())
        assertEquals(0, users.loadCount)
    }

    private fun clientSkills(resources: Map<String, ByteArray>): BackendClientSkills {
        val context = routeTestContext()
        return BackendClientSkills(
            registry = context.clientThreadRegistry,
            toolCallRepository = context.toolCallRepository,
            eventService = context.eventService,
            classLoader = ByteArrayResourceClassLoader(resources),
        )
    }

    private fun skillMarkdown(
        name: String,
        description: String,
        canonicalId: String,
    ): ByteArray = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: $description")
        appendLine("metadata:")
        appendLine("  souz.skill-id: $canonicalId")
        appendLine("  souz.transport: client-websocket")
        appendLine("  souz.category: CHAT")
        appendLine("  souz.timeout: PT5M")
        appendLine("---")
        appendLine("Instructions.")
    }.toByteArray()

    private companion object {
        const val USER = "resource-user"
    }
}

private class ByteArrayResourceClassLoader(
    private val resources: Map<String, ByteArray>,
) : ClassLoader(null) {
    override fun getResourceAsStream(name: String): InputStream? = resources[name]?.inputStream()
}

private class InMemoryProvider(vararg bundles: SkillBundle) : SkillRegistryRepository {
    private val bundles = bundles.associateBy { it.skillId }
    var loadCount: Int = 0

    override suspend fun listSkills(userId: String): List<StoredSkill> = bundles.values.map { bundle ->
        StoredSkill(
            userId = userId,
            skillId = bundle.skillId,
            manifest = bundle.manifest,
            bundleHash = SkillBundleHasher.hash(bundle),
            createdAt = Instant.EPOCH,
        )
    }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? {
        loadCount += 1
        return bundles[skillId]
    }

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill =
        error("This test repository is read-only.")

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = null

    override suspend fun saveValidation(record: SkillValidationRecord) {
        error("This test repository does not store validations.")
    }
}

private class DescriptionTool(description: String) : LLMToolSetup {
    override val fn = LLMRequest.Function(
        name = "collision",
        description = description,
        parameters = LLMRequest.Parameters(type = "object"),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall) = LLMRequest.Message(
        role = LLMMessageRole.function,
        content = "{}",
        name = functionCall.name,
    )
}

private class TestCatalog(
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
) : AgentToolCatalog
