# Skill inventory and approval

## Invariant

Every classic `GraphBasedAgent` turn runs direct-tool classification, Skill inventory/core-tool installation, then MCP injection. Classification narrows advertised direct-tool schemas; executable lookup may retain the host's wider catalog. Inventory lists enabled tool-backed IDs and user-scoped file-backed IDs. The skills graph selects relevant descriptions inside inventory preparation using description metadata and bounded recent conversation, with a host classifier and execution-LLM fallback. Selection failure retains ID-only discovery.

`GetSkillByName`, generic `RunSkillCommand`, and explicit `SpawnSubagent` selection load full file-backed bundles on demand. Hosts may pass `SkillApprovalGate` to require cached or fresh approval before exposing `SKILL.md` or executing bundled commands; without a gate, these paths use the loaded bundle directly. The backend intentionally passes no gate for classpath- and sandbox-backed Skills. When enabled, validation cache identity is the user, canonical skill ID, canonical bundle hash, and policy version. A changed bundle gets a different cache key. Changing validation rules requires a new policy version.

Hosts supply core Skill tools through `AgentCoreTools`, outside `AgentToolCatalog`; see the [skills graph](skills-oriented-graph.md) for exposure rules. Its optional spawn factory binds the parent's settings once per execution. Enabled compiled tools take precedence over stored bundles with the same ID; disabled tools do not hide a stored bundle. Selected child bundles are approved before prompt preparation and remain scoped to that child invocation.

## Why this is fragile

Loading bundles into the inventory would expand the prompt and trust surface. For hosts that enable approval, reusing it across users, hashes, or policies can return or execute content that was never approved.

The core tools merge compiled tools and stored bundles into one ID namespace. Category discovery covers filtered compiled-tool categories only; bundle detail and execution load the current bundle by exact Skill ID. Generic bundle execution must bind the current bundle identity internally before using the concrete command executor.

## Safe changes

- Keep discovery compact and user-scoped. ID listing reads no loose `SKILL.md`; description listing may read bounded frontmatter but never supporting files or bundle hashes.
- Escape IDs and selected descriptions as untrusted metadata. Descriptions are bounded discovery hints; full instructions require bundle lookup and approval where enabled.
- Bound classification history with shared head/tail truncation so long messages retain their original intent and recent details. Keep short messages intact and pass the execution's configured context size to the classification request.
- When approval is enabled, keep the order structural validation, static validation, then bounded LLM validation. Cache both approvals and rejections for the exact identity.
- When approval is enabled, treat a per-skill rejection as local to that skill lookup or invocation. Do not return `SKILL.md` or execute commands for rejected bundles.
- Rethrow coroutine cancellation from every phase.
- Keep supporting-file content out of inventory; load it only as part of bounded validation and execution paths.
- Keep `AgentCoreTools` out of `AgentToolCatalog`; graph nodes install them explicitly.
- Preserve compiled-tool precedence consistently in summary, detail, and execution paths. Load a stored bundle only after enabled-tool lookup fails.
- Never expose `activeSkills`, bundle hashes, storage paths, or supporting-file content through skill discovery. Generic execution binds those values internally.
- Validate composite command step shapes and references when parsing the manifest. Tool names and script paths are static; references can target declared inputs or earlier steps only. Share template traversal with execution so accepted syntax cannot resolve differently at runtime.

## Verification

Run `./gradlew :agent:test` for graph and approval changes. For core-tool changes, also run `./gradlew :sharedLogic:jvmTest`.
