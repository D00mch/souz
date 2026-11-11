package ru.souz.runtime.sandbox

import java.util.concurrent.ConcurrentHashMap
import ru.souz.llms.ToolInvocationMeta

fun interface ToolInvocationSandboxScopeResolver {
    fun resolve(meta: ToolInvocationMeta): SandboxScope
}

fun interface ToolInvocationRuntimeSandboxResolver {
    fun resolve(meta: ToolInvocationMeta): RuntimeSandbox

    fun fileSystem(meta: ToolInvocationMeta): SandboxFileSystem = resolve(meta).fileSystem

    companion object {
        fun fixed(sandbox: RuntimeSandbox): ToolInvocationRuntimeSandboxResolver =
            ToolInvocationRuntimeSandboxResolver { sandbox }
    }
}

class FactoryBackedToolInvocationRuntimeSandboxResolver(
    private val sandboxFactory: RuntimeSandboxFactory,
    private val scopeResolver: ToolInvocationSandboxScopeResolver,
) : ToolInvocationRuntimeSandboxResolver {
    // TODO: implement cache eviction
    private val sandboxesByScope = ConcurrentHashMap<SandboxScope, RuntimeSandbox>()

    override fun resolve(meta: ToolInvocationMeta): RuntimeSandbox {
        val scope = scopeResolver.resolve(meta)
        return sandboxesByScope.computeIfAbsent(scope) { sandboxFactory.create(it) }
    }

    override fun fileSystem(meta: ToolInvocationMeta): SandboxFileSystem {
        val scope = scopeResolver.resolve(meta)
        return sandboxesByScope[scope]?.fileSystem ?: sandboxFactory.fileSystem(scope) ?: resolve(meta).fileSystem
    }
}
