package ru.souz.skills.registry

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationSeverity
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.skills.validation.FileSystemSkillValidationRepository
import ru.souz.paths.DefaultSouzPaths
import ru.souz.tool.files.FilesToolUtil
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalSkillRegistryRepositoryTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `saves and loads skill bundle by user id and skill id`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-save-load-")
        val repository = LocalSkillRegistryRepository(stateRoot = stateRoot)
        val bundle = sampleBundle(skillId = SkillId("paper-summarize-academic"))

        val stored = repository.saveSkillBundle(userId = "user-1", bundle = bundle)
        val loaded = repository.loadSkillBundle(userId = "user-1", skillId = bundle.skillId)

        assertEquals("user-1", stored.userId)
        assertEquals(SkillBundleHasher.hash(bundle), stored.bundleHash)
        assertEquals(bundle, loaded)
        assertNull(repository.loadSkillBundle(userId = "user-2", skillId = bundle.skillId))
    }

    @Test
    fun `lists stored skill metadata without validation storage`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-list-")
        val repository = FileSystemSkillsRepository(paths = DefaultSouzPaths(stateRoot = stateRoot))
        val bundle = sampleBundle(skillId = SkillId("listable-skill"))

        repository.saveSkillBundle(userId = "user-1", bundle = bundle)

        val listed = repository.listSkills("user-1")
        val byId = repository.getSkill("user-1", bundle.skillId)
        val byName = repository.getSkillByName("user-1", bundle.manifest.name)

        assertEquals(1, listed.size)
        assertEquals(bundle.skillId, listed.single().skillId)
        assertEquals(bundle.manifest, byId?.manifest)
        assertEquals(bundle.skillId, byName?.skillId)
        assertNull(repository.getSkill("user-2", bundle.skillId))
    }

    @Test
    fun `saves and reads validation record from separate validation storage`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-validation-")
        val repository = FileSystemSkillValidationRepository(paths = DefaultSouzPaths(stateRoot = stateRoot))
        val record = SkillValidationRecord(
            userId = "user-1",
            skillId = SkillId("paper-summarize-academic"),
            bundleHash = "bundle-hash",
            status = SkillValidationStatus.APPROVED,
            policyVersion = "skills-policy/v1",
            validatorVersion = "skills-validator/v1",
            model = "gpt-test",
            reasons = listOf("passed"),
            findings = listOf(
                SkillValidationFinding(
                    code = "ok",
                    message = "Looks safe",
                    severity = SkillValidationSeverity.INFO,
                    filePath = "SKILL.md",
                )
            ),
            createdAt = Instant.parse("2026-05-02T12:00:00Z"),
        )

        repository.saveValidation(record)
        val loaded = repository.getValidation(
            userId = "user-1",
            skillId = record.skillId,
            bundleHash = record.bundleHash,
            policyVersion = record.policyVersion,
        )

        assertNotNull(loaded)
        assertEquals(record, loaded)
        assertNull(
            repository.getValidation(
                userId = "user-1",
                skillId = record.skillId,
                bundleHash = "other-hash",
                policyVersion = record.policyVersion,
            )
        )
    }

    @Test
    fun `invalidates older approved validations without touching active bundle`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-validation-invalidate-")
        val repository = FileSystemSkillValidationRepository(paths = DefaultSouzPaths(stateRoot = stateRoot))
        val active = sampleValidationRecord(bundleHash = "active-hash")
        val old = sampleValidationRecord(bundleHash = "old-hash")
        repository.saveValidation(active)
        repository.saveValidation(old)

        repository.invalidateOtherValidations(
            userId = active.userId,
            skillId = active.skillId,
            activeBundleHash = active.bundleHash,
            policyVersion = active.policyVersion,
            reason = "new bundle",
        )

        val reloadedActive = repository.getValidation(active.userId, active.skillId, active.bundleHash, active.policyVersion)
        val reloadedOld = repository.getValidation(old.userId, old.skillId, old.bundleHash, old.policyVersion)

        assertEquals(SkillValidationStatus.APPROVED, reloadedActive?.status)
        assertEquals(SkillValidationStatus.STALE, reloadedOld?.status)
        assertEquals(listOf("passed", "new bundle"), reloadedOld?.reasons)
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(Path.of(FilesToolUtil.homeStr), prefix).also(createdPaths::add)

    private fun sampleBundle(skillId: SkillId): SkillBundle = SkillBundle.fromFiles(
        skillId = skillId,
        files = listOf(
            SkillFile(
                normalizedPath = "SKILL.md",
                content = """
                    ---
                    name: ${skillId.value}
                    description: Description for ${skillId.value}
                    ---
                    Skill instructions.
                """.trimIndent().toByteArray(Charsets.UTF_8),
            ),
            SkillFile(
                normalizedPath = "README.md",
                content = "Read me".toByteArray(Charsets.UTF_8),
            ),
        ),
    )

    private fun sampleValidationRecord(bundleHash: String): SkillValidationRecord = SkillValidationRecord(
        userId = "user-1",
        skillId = SkillId("paper-summarize-academic"),
        bundleHash = bundleHash,
        status = SkillValidationStatus.APPROVED,
        policyVersion = "skills-policy/v1",
        validatorVersion = "skills-validator/v1",
        model = "gpt-test",
        reasons = listOf("passed"),
        findings = listOf(
            SkillValidationFinding(
                code = "ok",
                message = "Looks safe",
                severity = SkillValidationSeverity.INFO,
                filePath = "SKILL.md",
            )
        ),
        createdAt = Instant.parse("2026-05-02T12:00:00Z"),
    )
}
