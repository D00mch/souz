package ru.souz.backend.scheduler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.backend.hooks.HookConfig
import ru.souz.backend.hooks.HookDefinition
import ru.souz.backend.hooks.HookDefinitions
import ru.souz.backend.hooks.HookService
import ru.souz.backend.hooks.LoadedHook
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.SandboxScope

/** Locks cover only this process's task mutations; operator edits are checked before each write. */
internal class ScheduledHookFiles(
    private val sandboxes: RuntimeSandboxFactory,
    private val config: HookConfig,
    private val definitions: HookDefinitions,
    private val hooks: HookService,
) {
    private val lockRegistry = Mutex()
    private val locks = mutableMapOf<String, Mutex>()

    suspend fun read(owner: String): Map<String, LoadedHook> {
        taskCheck(owner in config.owners, "hooks_not_enabled_for_user")
        val loaded = definitions.load(owner)
        taskCheck(loaded.map { it.definition.hookId }.distinct().size == loaded.size, "hook_conflict")
        return loaded.associateBy { it.definition.hookId }
    }

    suspend fun <T> mutate(owner: String, block: suspend (Workspace) -> T): T {
        taskCheck(owner in config.owners, "hooks_not_enabled_for_user")
        val sandbox = withContext(Dispatchers.IO) { sandboxes.create(SandboxScope(userId = owner)) }
        val root = sandbox.runtimePaths.workspaceRootPath ?: throw TaskFailure("hook_workspace_unavailable")
        val key = if (sandbox.mode == SandboxMode.LOCAL) "local:$root" else "docker:$owner:$root"
        val lock = lockRegistry.withLock { locks.getOrPut(key) { Mutex() } }
        return lock.withLock { block(Workspace(owner, sandbox, root)) }
    }

    inner class Workspace(private val owner: String, sandbox: RuntimeSandbox, root: String) {
        private val fs = sandbox.fileSystem
        private val hooksPath = "$root/hooks"
        private val log = LoggerFactory.getLogger(ScheduledHookFiles::class.java)

        suspend fun find(id: String): LoadedHook? {
            val matches = definitions.load(owner).filter { it.definition.hookId == id }
            taskCheck(matches.size <= 1, "hook_conflict")
            if (matches.isEmpty()) {
                val path = withContext(Dispatchers.IO) { fs.resolvePath("$hooksPath/$id/hook.yaml") }
                // A foreign or renamed definition at the managed path is not a missing hook.
                taskCheck(!path.exists && !path.isSymbolicLink, "hook_configuration_changed")
            }
            return matches.singleOrNull()
        }

        suspend fun create(definition: HookDefinition, beforeWrite: () -> Unit): LoadedHook {
            read(owner) // Fail before writing when any existing definition is invalid.
            val content = definitions.serialize(definition)
            withContext(Dispatchers.IO) {
                var root = fs.resolvePath(hooksPath)
                taskCheck(!root.isSymbolicLink && fs.isPathSafe(root), "hook_workspace_unavailable")
                if (!root.exists) fs.createDirectory(root)
                root = fs.resolveExistingDirectory(hooksPath)
                taskCheck(fs.listDescendants(root, maxDepth = 1).count { it.isDirectory } < 100, "hook_limit_exceeded")
                val directory = fs.resolvePath("${root.path}/${definition.hookId}")
                taskCheck(!directory.exists && !directory.isSymbolicLink && directory.parentPath == root.path && fs.isPathSafe(directory), "hook_conflict")
                beforeWrite()
                fs.createDirectory(directory)
                val path = fs.resolvePath("${directory.path}/hook.yaml")
                taskCheck(!path.exists && fs.isPathSafe(path) && path.parentPath == directory.path, "hook_conflict")
                fs.writeTextAtomically(path, content, log)
            }
            hooks.reload(owner)
            taskCheck(hooks.definition(owner, definition.hookId) == definition, "hook_reload_failed")
            return find(definition.hookId) ?: throw TaskFailure("hook_reload_failed")
        }

        suspend fun replace(expected: LoadedHook, replacement: HookDefinition): LoadedHook {
            val current = unchanged(expected)
            withContext(Dispatchers.IO) {
                fs.writeTextAtomically(fs.resolveExistingFile(requireNotNull(current.sourcePath)), definitions.serialize(replacement), log)
            }
            hooks.reload(owner)
            taskCheck(hooks.definition(owner, replacement.hookId) == replacement, "hook_reload_failed")
            return find(replacement.hookId) ?: throw TaskFailure("hook_reload_failed")
        }

        suspend fun remove(expected: LoadedHook) {
            val current = unchanged(expected)
            withContext(Dispatchers.IO) {
                val path = fs.resolveExistingFile(requireNotNull(current.sourcePath))
                val directory = fs.resolveExistingDirectory(requireNotNull(path.parentPath))
                fs.delete(path)
                if (fs.listDescendants(directory, maxDepth = 1, includeHidden = true).isEmpty()) fs.delete(directory)
            }
            hooks.reload(owner)
            taskCheck(hooks.definition(owner, current.definition.hookId) == null, "hook_reload_failed")
        }

        private suspend fun unchanged(expected: LoadedHook): LoadedHook {
            val current = find(expected.definition.hookId) ?: throw TaskFailure("hook_configuration_changed")
            taskCheck(current.definition == expected.definition && current.revision == expected.revision && current.sourcePath == expected.sourcePath, "hook_configuration_changed")
            return current
        }
    }
}
