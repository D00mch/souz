package ru.souz.skills.registry

import java.time.Clock
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProviderImpl
import ru.souz.paths.DefaultSouzPaths
import ru.souz.paths.SouzPaths
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox

/**
 * Compatibility wrapper that keeps the legacy local registry entry point while
 * delegating storage and loading to [SandboxSkillRegistryRepository].
 */
class LocalSkillRegistryRepository(
    paths: SouzPaths = DefaultSouzPaths(),
    clock: Clock = Clock.systemUTC(),
    sandbox: LocalRuntimeSandbox = LocalRuntimeSandbox(
        scope = SandboxScope.localDefault(),
        settingsProvider = SettingsProviderImpl(ConfigStore),
        homePath = DefaultSouzPaths.homeDirectory(),
        stateRoot = paths.stateRoot,
    ),
    private val delegate: SkillRegistryRepository = SandboxSkillRegistryRepository(
        sandbox = sandbox,
        clock = clock,
    ),
) : SkillRegistryRepository by delegate
