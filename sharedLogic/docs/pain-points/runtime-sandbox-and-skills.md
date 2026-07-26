# Runtime sandbox and skills

## Invariants

- Tools resolve a `RuntimeSandbox` from the current `ToolInvocationMeta`. The resolver maps invocation metadata to `SandboxScope` and may cache sandboxes by scope; tools must not cache a resolved path or sandbox for later users or conversations.
- `FileSystemSkillRegistryRepository` and `RunSkillCommand` must use the same `SkillStorageScope`. `SINGLE_USER` and `USER_SCOPED` have different bundle and validation paths.
- Skill metadata, immutable hash-addressed bundles, and validation records stay behind `SandboxFileSystem`. Bundle loading rejects escaping paths, symlinks, non-regular files, binary content, and invalid UTF-8.
- `RunSkillCommand` accepts only a skill activated for the current turn and keeps its script and working directory within that skill bundle.
- The legacy command and generic `RunSkillCommand` share one concrete executor. Generic bundle calls bind the current ID, hash, supporting paths, and active-skill authorization internally, then return the complete `SandboxCommandResult` for later Knowledge offloading.
- `GetSkills`, `GetKnowledge`, and generic `RunSkillCommand` are separately tagged and remain outside `AgentToolCatalog`. Skill tools trust bundles accepted by the registry loader and do not use the legacy validation cache.

- `ToolGetSkills` and `ToolInvokeSkill` implement `LLMToolSetup` directly so their structured results are serialized exactly once. `ToolInvokeSkill` must also preserve the complete `LLMRequest.Message`, including attachments, when delegating to a compiled tool. `ToolSetup.toGiga()` cannot preserve these behaviors because its contract returns `String` and serializes that value again.
- `SandboxConversationKnowledgeStore` requires invocation user and conversation identity, resolves the sandbox for every operation, and scopes immutable UUID entries under fixed-length SHA-256 user and conversation keys. Entries remain until targeted conversation cleanup.
- Knowledge retains at most 1 MiB of UTF-8 content. Complete entries keep the full result. Truncated entries keep as many whole Unicode code points as fit in separate 512 KiB head and tail budgets and omit the middle. `originalLength` and `storedLength` use UTF-16 `String` indices; boundary selection is internal to the store.
- The skills-oriented graph offloads tool-result content only above 4,096 UTF-8 bytes and never re-offloads `GetKnowledge` responses. A Knowledge reference remains usable until exact conversation cleanup; clearing context expires its referenced entries immediately.
- `GetKnowledge` either returns all retained content or searches it with case-sensitive RE2 syntax and inline flags. Regex context defaults to 256 UTF-16 code units per side with a `0..4096` range; match count defaults to 20 with a `1..100` range. Match and excerpt offsets are original UTF-16 start-inclusive/end-exclusive indices. Truncated head and tail are searched as separate inputs, so matches cannot bridge the omitted middle and anchors apply independently to each retained segment.

## Why this is fragile

The same contracts back three different runtimes. JVM hosts select local or Docker mode; Android uses app-private filesystem roots and executes shell skills with POSIX `/system/bin/sh`. Android does not provide the Python or Node skill runtimes, and its `BASH` option is compatibility naming rather than a GNU Bash guarantee. A storage-scope mismatch makes an installed skill visible to activation but unavailable to command execution.

Skill discovery applies `AgentToolsFilter` on every discovery and invocation. Enabled compiled tools take precedence over same-ID stored bundles; disabled tools do not hide stored bundles. Summary discovery reads registry metadata only, while detail and execution load the current bundle.

Docker mounts `/souz`, so bundled development skills live under `/opt/souz/skills` in the image and are seeded into registry-compatible state on startup. Seeding is non-overwriting: an existing skill record remains authoritative.

Local and Android sandboxes can share physical state roots across logical scopes, and Backend scope resolution can omit conversation identity. Knowledge isolation therefore comes from its internal hashed user/conversation path rather than `RuntimeSandbox.scope`. Local process execution is not a cross-tenant filesystem security boundary.

JVM local mode supports `SandboxConversationKnowledgeStore` only when `stateRootPath` is located beneath `homePath`. `LocalSandboxFileSystem` permits filesystem access only beneath the home root, so a local state root outside it cannot be read, written, or cleared through the Knowledge store. This unsupported configuration remains a limitation to revisit if external local state roots are needed.

## Safe changes

- Pass `ToolInvocationMeta` through every file or command operation and resolve paths at the call boundary.
- Preserve path containment and bundle validation when adding repository or command features.
- Keep Android skill scripts POSIX-compatible unless Android explicitly gains another runtime.
- When changing skill layout or scope, update the repository, command tool, host DI wiring, Docker entrypoint, and tests together.
- Keep the separately tagged skill tools out of the catalog until their graph owns them. Derive file-backed arguments from the legacy command schema while excluding model-supplied identity and authorization fields.
- Do not adapt `ToolGetSkills` or `ToolInvokeSkill` through `ToolSetup.toGiga()` unless `ToolSetup` gains structured result and attachment-preserving delegation support.
- Keep Knowledge paths internal: callers provide opaque UUIDs, never filesystem paths. Preserve atomic JSON writes, the UTF-8 retention cap, whole-code-point head/tail boundaries, record validation, and conversation-only recursive cleanup.
- Build Knowledge paths as slash-delimited sandbox strings. Docker runtime paths are POSIX container paths and must not pass through host `Path` semantics.
- Treat missing Knowledge conversation scope as unavailable; never fall back to user-wide storage. Do not add TTL or startup cleanup without defining how retained references in conversation history expire.
- Keep Knowledge regex matching on RE2/J. Do not switch to a backtracking engine or join truncated segments before matching; either change would invalidate its bounded-work and original-offset guarantees.

## Verification

Run:

```zsh
./gradlew :sharedLogic:jvmTest --tests 'ru.souz.runtime.sandbox.*' --tests 'ru.souz.skills.*' --tests 'ru.souz.tool.skills.*'
./gradlew :sharedLogic:compileAndroidMain
```

For Knowledge storage changes, include `--tests 'ru.souz.knowledge.*'` in the JVM test selection.

For Docker behavior, build the sandbox image and run the opt-in Docker tests described in the module `AGENTS.md`.
