package ru.souz.agent.skills.registry

import ru.souz.agent.skills.validation.SkillValidationStore

/**
 * Combined persistence contract for user-installed Skills.
 *
 * Most call sites should depend on [SkillBundleProvider] or [SkillValidationStore] directly.
 * This facade is kept for storage implementations that own both bundle persistence and the
 * validation cache.
 */
interface SkillRegistryRepository : WritableSkillBundleProvider, SkillValidationStore
