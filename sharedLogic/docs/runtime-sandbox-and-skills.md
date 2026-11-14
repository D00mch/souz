# Runtime sandbox and skills

## Invariants

- Resolve filesystem and command access from the current `ToolInvocationMeta`. The resolver can cache by scope. Tools must resolve again at each invocation. [Invocation resolver](../src/commonJvmMain/kotlin/ru/souz/runtime/sandbox/ToolInvocationRuntimeSandboxResolver.kt)
- Production discovery uses the storage-only filesystem view. Listing IDs and descriptions must leave user directories untouched and avoid Docker, even for an empty registry. ID inventory skips loose `SKILL.md` reads, supporting files, and bundle hashing. Description discovery reads stored metadata or bounded UTF-8 frontmatter and isolates failures per Skill. Exact loading, writes, and execution use the runtime resolver. Custom resolvers can provide a storage-only view to avoid the runtime fallback. [Registry](../src/commonJvmMain/kotlin/ru/souz/skills/registry/FileSystemSkillRegistryRepository.kt)
- Keep metadata, immutable hash-addressed bundles, and validation records behind `SandboxFileSystem`. Bundle loading rejects escaping paths, symlinks, non-regular files, binary content, and invalid UTF-8. The registry and command executor share the single-user layout, including loose bundles. Keep the script and working directory inside the selected bundle. [Command executor](../src/commonJvmMain/kotlin/ru/souz/tool/skills/SkillCommandExecutor.kt)
- `RunSkillCommand` uses the caller's scoped bundle loader. Follow the [Skill resolution and approval rules](../../agent/docs/skill-activation.md) and [child capability restrictions](../../agent/docs/subagents.md#safe-changes). [Invocation](../src/commonJvmMain/kotlin/ru/souz/tool/skills/ToolInvokeSkill.kt)
- Keep Knowledge references opaque and conversation-scoped. The sandbox store resolves each operation from user and conversation identity and uses hashed paths to isolate scopes sharing a physical root. Preserve atomic writes, immutable UUID records, and targeted cleanup. Missing conversation scope makes storage unavailable. Any TTL or startup cleanup needs an expiration contract for references in conversation history. Hosts supply the store: desktop binds the sandbox store and backend binds PostgreSQL. Retrieval tools never select storage implementations; preserve the shared codec for desktop/backend compatibility. [Sandbox store](../src/commonJvmMain/kotlin/ru/souz/knowledge/SandboxConversationKnowledgeStore.kt) · [Backend Knowledge](../../backend/docs/conversation-knowledge.md) · [Offloading](../../agent/docs/skills-oriented-graph.md#tool-result-offloading)
- Knowledge retains up to 1 MiB of UTF-8 with whole code points in separate head and tail segments when truncated. References survive archive and remain valid until targeted cleanup, including context clear. Retrieval returns all retained content. Search uses bounded, case-sensitive RE2/J with inline flags and original UTF-16 offsets. Search truncated segments independently to preserve anchors and avoid matches across omitted text. [Retention codec](../src/commonJvmMain/kotlin/ru/souz/knowledge/KnowledgeRecordCodec.kt) · [Search tool](../src/commonJvmMain/kotlin/ru/souz/tool/knowledge/ToolSearchKnowledge.kt)

`SearchMemory` is universal to both graphs. The host may return `memory_unavailable`. Desktop search uses the persistent owner and only global plus current-conversation session scopes. [Core tools](../../agent/src/main/kotlin/ru/souz/agent/AgentCoreTools.kt)

## Why this is fragile

- LOCAL process execution provides no cross-tenant filesystem isolation. LOCAL sandbox Knowledge requires `stateRootPath` under `homePath` because filesystem access is restricted to the home root. Keep DOCKER paths slash-delimited on every host.
- DOCKER seeds bundled Skills on runtime startup without overwriting existing records. Provision the sandbox explicitly for first-use discovery of image-bundled Skills. Exact-ID lookup can start the runtime, while inventory reads only files already on the host. [Docker entrypoint](../docker/entrypoint.sh)
- Windows LOCAL Python uses `python`. Use DOCKER for shell-script Skills on Windows because Git Bash uses MSYS paths and process IDs and its children can escape process-tree cleanup.
- Interrupt local process waits on cancellation. On timeout or cancellation, capture descendants before stopping the parent, then terminate the captured children before draining output. Stopping an awaited child first can let the parent spawn new work. After the drain grace, terminate descendants holding inherited pipes before closing the streams because Windows can block on close. Keep stdin file-backed to avoid blocked writers and preserve the result if cleanup fails. [Local process execution](../src/jvmMain/kotlin/ru/souz/runtime/sandbox/local/LocalSandboxCommandExecutor.kt) · [Output capture](../src/commonJvmMain/kotlin/ru/souz/runtime/sandbox/SandboxCommandOutputCapture.kt)

## Safe changes

- Coordinate layout changes across the registry, command executor, host wiring, Docker entrypoint, and tests. Keep bundle identity and authorization out of model-facing command arguments. Derive the command schema from `SkillCommandExecutor.Args`.
- Preserve structured results with a single serialization and forward the full delegated message, including attachments. [Invocation](../src/commonJvmMain/kotlin/ru/souz/tool/skills/ToolInvokeSkill.kt) · [Core-tool boundary](../../agent/docs/skills-oriented-graph.md#invariant)
- Keep composites on the invoking tool's filtered snapshot, including request-scoped client tools. Validate tool names before any step runs, propagate cancellation, and give scripts only the remaining command deadline. [Composite command contract](../../docs/composite-skill-commands.md) · [Composite executor](../src/commonJvmMain/kotlin/ru/souz/tool/skills/CompositeCommandExecutor.kt)

## Verification

```sh
./gradlew :sharedLogic:jvmTest --tests 'ru.souz.runtime.sandbox.*' --tests 'ru.souz.skills.*' --tests 'ru.souz.tool.skills.*' --tests 'ru.souz.tool.memory.*'
```

For Knowledge changes, add `--tests 'ru.souz.knowledge.*' --tests 'ru.souz.tool.knowledge.*'` and run the [backend Knowledge checks](../../backend/docs/conversation-knowledge.md#verification). For Docker changes, run the [module's opt-in Docker checks](../AGENTS.md#verification).

Use real processes to verify deadlines and descendant cleanup because virtual time and mocks cannot expose blocked process waits or inherited pipes. Wait for parent and child readiness before testing cancellation, and keep the watchdog independent of the command timeout. Distinguish Linux zombies from running children. For failures, check the command variant, waiting phase, result, and remaining processes in CI's JVM test reports. [Local process tests](../src/test/kotlin/ru/souz/runtime/sandbox/local/LocalSandboxCommandExecutorTest.kt)
