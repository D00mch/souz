package ru.souz.skills.registry

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver

fun fileSystemSkillRegistryDiModule(): DI.Module = DI.Module("fileSystemSkillRegistry") {
    bindSingleton<SkillRegistryRepository> {
        FileSystemSkillRegistryRepository(
            sandboxResolver = instance<ToolInvocationRuntimeSandboxResolver>(),
        )
    }
}
