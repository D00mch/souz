package ru.souz.backend.agent.runtime.conversation

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.registry.StoredSkill

class CompositeSkillBundleProviderTest {
    @Test
    fun `earlier provider wins collisions in metadata inventory and bundle lookup`() = runTest {
        val bundledShared = bundle("shared", "bundled")
        val filesystemShared = bundle("shared", "filesystem")
        val bundledOnly = bundle("bundled-only", "bundled")
        val filesystemOnly = bundle("filesystem-only", "filesystem")
        val provider = CompositeSkillBundleProvider(
            listOf(
                FakeSkillBundleProvider(listOf(bundledShared, bundledOnly)),
                FakeSkillBundleProvider(listOf(filesystemShared, filesystemOnly)),
            )
        )

        assertEquals(
            listOf("shared", "bundled-only", "filesystem-only"),
            provider.listSkills(USER_ID).map { it.skillId.value },
        )
        assertEquals(
            listOf("shared", "bundled-only", "filesystem-only"),
            provider.listSkillInventoryIds(USER_ID).map { it.value },
        )
        assertSame(bundledShared, provider.loadSkillBundle(USER_ID, SkillId("shared")))
        assertSame(filesystemOnly, provider.loadSkillBundle(USER_ID, SkillId("filesystem-only")))
    }

    @Test
    fun `provider list is snapshotted defensively`() = runTest {
        val first = FakeSkillBundleProvider(listOf(bundle("first", "first")))
        val providers = mutableListOf<SkillBundleProvider>(first)
        val composite = CompositeSkillBundleProvider(providers)

        providers += FakeSkillBundleProvider(listOf(bundle("late", "late")))

        assertEquals(listOf("first"), composite.listSkillInventoryIds(USER_ID).map { it.value })
    }
}

private class FakeSkillBundleProvider(
    private val bundles: List<SkillBundle>,
) : SkillBundleProvider {
    override suspend fun listSkills(userId: String): List<StoredSkill> = bundles.map { bundle ->
        StoredSkill(
            userId = userId,
            skillId = bundle.skillId,
            manifest = bundle.manifest,
            bundleHash = SkillBundleHasher.hash(bundle),
            createdAt = Instant.EPOCH,
        )
    }

    override suspend fun listSkillInventoryIds(userId: String): List<SkillId> = bundles.map { it.skillId }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? =
        bundles.firstOrNull { it.skillId == skillId }
}

private fun bundle(skillId: String, body: String): SkillBundle = SkillBundle.fromFiles(
    skillId = SkillId(skillId),
    files = listOf(
        SkillFile(
            normalizedPath = "SKILL.md",
            content = """
                ---
                name: $skillId
                description: Test Skill $skillId
                ---
                $body
            """.trimIndent().toByteArray(),
        )
    ),
)

private const val USER_ID = "user-a"
