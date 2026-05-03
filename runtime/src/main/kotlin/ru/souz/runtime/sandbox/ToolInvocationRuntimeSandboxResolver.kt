package ru.souz.runtime.sandbox

import ru.souz.llms.ToolInvocationMeta

fun interface ToolInvocationSandboxScopeResolver {
    fun resolve(meta: ToolInvocationMeta): SandboxScope
}

fun interface ToolInvocationRuntimeSandboxResolver {
    fun resolve(meta: ToolInvocationMeta): RuntimeSandbox

    companion object {
        fun fixed(sandbox: RuntimeSandbox): ToolInvocationRuntimeSandboxResolver =
            ToolInvocationRuntimeSandboxResolver { sandbox }
    }
}

class FactoryBackedToolInvocationRuntimeSandboxResolver(
    private val sandboxFactory: RuntimeSandboxFactory,
    private val scopeResolver: ToolInvocationSandboxScopeResolver,
) : ToolInvocationRuntimeSandboxResolver {
    override fun resolve(meta: ToolInvocationMeta): RuntimeSandbox =
        sandboxFactory.create(scopeResolver.resolve(meta))
}
