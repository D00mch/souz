# Skill inventory and approval

## Invariant

Every classic `GraphBasedAgent` turn runs direct-tool classification, Skill inventory/core-tool installation, then MCP injection. Classification narrows only direct tool schemas advertised to the model; exact skill lookup and invocation may use a wider host-supplied resolution catalog. The compact Skill inventory is appended to the effective system message and lists enabled direct tool-backed Skill IDs plus opaque IDs returned by the effective `SkillBundleProvider` without loading file-backed bundles or manifest text.

`GetSkillByName` and generic `RunSkillCommand` are the only paths that load full file-backed bundle content for model use. Both check enabled compiled adapters before loading a stored bundle. Bundle fallback requires cached or fresh approval through the shared approval gate before returning `SKILL.md` or executing bundled commands. Validation cache identity is the user, canonical skill ID, canonical bundle hash, and policy version. A changed bundle gets a different cache key. Changing validation rules requires a new policy version.

Separately tagged `GetSkillByName`, `GetSkillsByCategory`, `GetSkillsNamesByCategory`, `GetKnowledge`, `SearchKnowledge`, `SearchMemory`, and generic `RunSkillCommand` tools remain outside `AgentToolCatalog`. `SkillsGraphBasedAgent` exposes all seven. `GraphBasedAgent` always exposes `GetSkillByName`, `GetKnowledge`, `SearchKnowledge`, `SearchMemory`, and generic `RunSkillCommand` in addition to classified direct tools. Enabled compiled tools take precedence over stored bundles with the same ID; disabled tools do not hide a stored bundle.

Hosts that expose separate direct and exact-resolution catalogs must reject ID collisions between those views when the same ID would invoke different compiled adapters. Exact Skill ID collisions are resolved by compiled precedence.

## Why this is fragile

Loading bundles into the inventory would expand the prompt and trust surface. Reusing approval across users, hashes, or policies can return or execute content that was never approved.

The separately tagged tools merge compiled tools and stored bundles into one ID namespace. Category discovery covers filtered compiled-tool categories from the host's resolution catalog; bundle detail and execution load the current bundle by exact Skill ID only after enabled compiled lookup fails. Generic bundle execution must bind the current bundle identity internally before reusing the legacy command implementation.

## Safe changes

- Keep prompt inventory compact and user-scoped. Use an ID-only registry path and do not load `SKILL.md` or supporting files while rendering the inventory.
- Render file-backed Skill IDs as opaque escaped data only. Do not copy unapproved manifest names or descriptions into the system prompt.
- Keep the order structural validation, static validation, then bounded LLM validation. Cache both approvals and rejections for the exact identity.
- Treat a per-skill rejection as local to that skill lookup or invocation. Do not return `SKILL.md` or execute commands for rejected bundles.
- Rethrow coroutine cancellation from every phase.
- Keep supporting-file content out of inventory; load it only as part of bounded validation and execution paths.
- Keep the separately tagged core tools out of `AgentToolCatalog`; graph nodes install them explicitly.
- Preserve compiled-tool precedence consistently in summary, detail, and execution paths. Load a stored bundle only after enabled-tool lookup fails.
- Host-reviewed compiled adapters may be omitted from the direct catalog while appearing in exact skill resolution. Approval is required only when detail lookup or execution falls back to stored bundle content.
- Never expose `activeSkills`, bundle hashes, storage paths, or supporting-file content through skill discovery. Generic execution binds those values internally.

## Verification

Run `./gradlew :agent:test` for graph and approval changes. For separately tagged runtime-tool changes, also run `./gradlew :sharedLogic:jvmTest :sharedLogic:compileAndroidMain`.
