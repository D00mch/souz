package ru.souz.agent.skills

import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.implementations.activation.FakeSkillLlmValidator
import ru.souz.agent.skills.implementations.bundle.SkillBundleLoader
import ru.souz.agent.skills.implementations.bundle.skillFixturePath
import ru.souz.agent.skills.implementations.registry.InMemorySkillRegistryRepository
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SkillApprovalGateTest {
    @Test
    fun `approval validates bundle once and reuses cache`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        val bundle = fixtureBundle()
        repository.saveSkillBundle(USER_ID, bundle)
        val validator = FakeSkillLlmValidator.approving()
        val gate = SkillApprovalGate(repository, validator)

        repeat(2) {
            val result = gate.ensureApproved(input(bundle))
            assertIs<SkillApprovalGate.Result.Approved>(result)
        }

        assertEquals(1, validator.invocationCount)
    }

    @Test
    fun `approval revalidates when bundle hash changes`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        val firstBundle = fixtureBundle()
        repository.saveSkillBundle(USER_ID, firstBundle)
        val validator = FakeSkillLlmValidator.approving()
        val gate = SkillApprovalGate(repository, validator)

        assertIs<SkillApprovalGate.Result.Approved>(gate.ensureApproved(input(firstBundle)))

        val changedBundle = SkillBundle.fromFiles(
            skillId = SKILL_ID,
            files = firstBundle.files + SkillFile("notes.txt", "changed".toByteArray()),
        )
        repository.saveSkillBundle(USER_ID, changedBundle)

        assertIs<SkillApprovalGate.Result.Approved>(gate.ensureApproved(input(changedBundle)))
        assertEquals(2, validator.invocationCount)
    }

    @Test
    fun `approval rejects cached rejection without calling validator`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        val bundle = fixtureBundle()
        val bundleHash = SkillBundleHasher.hash(bundle)
        repository.saveSkillBundle(USER_ID, bundle)
        repository.saveValidation(
            SkillValidationRecord(
                userId = USER_ID,
                skillId = SKILL_ID,
                bundleHash = bundleHash,
                status = SkillValidationStatus.REJECTED,
                policyVersion = "skills-policy/v1",
                validatorVersion = "skills-validator/v1",
                reasons = listOf("Rejected earlier."),
                createdAt = java.time.Instant.EPOCH,
            )
        )
        val validator = FakeSkillLlmValidator.approving()
        val gate = SkillApprovalGate(repository, validator)

        val result = gate.ensureApproved(input(bundle))

        val rejected = assertIs<SkillApprovalGate.Result.Rejected>(result)
        assertEquals("Rejected earlier.", rejected.reason)
        assertEquals(0, validator.invocationCount)
    }

    private fun input(bundle: SkillBundle): SkillApprovalGate.Input = SkillApprovalGate.Input(
        userId = USER_ID,
        skillId = SKILL_ID,
        bundle = bundle,
    )

    private fun fixtureBundle(): SkillBundle = SkillBundleLoader().loadDirectory(
        skillId = SKILL_ID,
        rootDirectory = skillFixturePath("paper-summarize-academic"),
    )

    private companion object {
        const val USER_ID = "user-1"
        val SKILL_ID = SkillId("paper-summarize-academic")
    }
}
