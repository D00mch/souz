package ru.souz.backend.agent.runtime

import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationSandboxScopeResolver

object BackendSandboxScopeResolver : ToolInvocationSandboxScopeResolver {
    override fun resolve(meta: ToolInvocationMeta): SandboxScope {
        return SandboxScope(userId = meta.userId.trim())
    }
}
