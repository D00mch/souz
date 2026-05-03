package ru.souz.backend.agent.runtime

import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationSandboxScopeResolver

object BackendSandboxScopeResolver : ToolInvocationSandboxScopeResolver {
    override fun resolve(meta: ToolInvocationMeta): SandboxScope {
        val userId = meta.userId
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("Backend sandbox resolution requires ToolInvocationMeta.userId.")
        return SandboxScope(userId = userId)
    }
}
