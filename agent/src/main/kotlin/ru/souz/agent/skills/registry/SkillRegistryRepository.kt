package ru.souz.agent.skills.registry

import ru.souz.agent.skills.validation.SkillValidationRepository

/**
 * Combined persistence contract used by [ru.souz.agent.skills.SkillActivationPipeline].
 *
 * A single implementation owns both the user-visible skill catalog and the persisted
 * validation cache keyed by user, skill id, bundle hash, and policy version.
 */
interface SkillRegistryRepository : SkillsRepository, SkillValidationRepository
