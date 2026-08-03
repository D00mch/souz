package ru.souz.backend.client

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.backend.TestSkillRegistryRepository
import ru.souz.backend.http.routeTestContext
import ru.souz.tool.ToolCategory

class BackendClientToolCatalogTest {
    @Test
    fun `catalog projects client Skills supplied by the Skill registry`() = runTest {
        val bundle = SkillBundle.fromFiles(
            skillId = SkillId("device.custom.action"),
            files = listOf(
                SkillFile(
                    normalizedPath = "SKILL.md",
                    content = """
                        ---
                        name: custom-client-action
                        description: Perform a custom client action.
                        metadata:
                          souz.transport: client-websocket
                          souz.category: APPLICATIONS
                          souz.timeout: PT7S
                        ---

                        # Custom action

                        Pass the action payload to the active client.
                    """.trimIndent().toByteArray(),
                )
            ),
        )
        val stored = StoredSkill(
            userId = "user-1",
            skillId = bundle.skillId,
            manifest = bundle.manifest,
            bundleHash = SkillBundleHasher.hash(bundle),
            createdAt = Instant.EPOCH,
        )
        val repository = object : SkillRegistryRepository by TestSkillRegistryRepository {
            override suspend fun listSkills(userId: String): List<StoredSkill> = listOf(stored)

            override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? =
                bundle.takeIf { it.skillId == skillId }
        }
        val context = routeTestContext()

        val catalog = BackendClientToolCatalogFactory(
            skillRegistryRepository = repository,
            registry = context.clientThreadRegistry,
            toolCallRepository = context.toolCallRepository,
            eventService = context.eventService,
        ).create("user-1")

        val tool = catalog.toolsByCategory.getValue(ToolCategory.APPLICATIONS)
            .getValue("device.custom.action")
        assertEquals(setOf("device.custom.action"), catalog.toolsByCategory.values.flatMap { it.keys }.toSet())
        assertContains(tool.fn.description, "Perform a custom client action.")
        assertContains(tool.fn.description, "Pass the action payload")
    }
}
