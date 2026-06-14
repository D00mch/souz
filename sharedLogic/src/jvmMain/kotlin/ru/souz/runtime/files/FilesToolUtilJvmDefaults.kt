package ru.souz.runtime.files

import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationSandboxScopeResolver

@Suppress("FunctionName")
fun FilesToolUtil(settingsProvider: SettingsProvider): FilesToolUtil =
    FilesToolUtil(
        sandboxFactory = DefaultRuntimeSandboxFactory(settingsProvider = settingsProvider),
        scopeResolver = ToolInvocationSandboxScopeResolver {
            SandboxScope.localDefault()
        },
    )
