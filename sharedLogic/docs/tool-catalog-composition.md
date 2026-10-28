# Tool catalog composition

## Invariants

`AgentToolCatalog` in `:agent` owns immutable tool membership and name indexing. Construction, filtering, transformation, and composition preserve `ToolCategory.entries` order and reject duplicate function names. Tool instances retain their invocation behavior; catalog immutability applies to the outer and inner maps.

Runtime factories expose their catalog through `.catalog`. Execution-bound LLM tools use the current execution's model and provider; process-owned runtime catalogs do not retain execution settings.

Skill lookup, invocation, and subagent selection read a host-supplied catalog provider. Each operation resolves one immutable snapshot; children retain only the capabilities selected at spawn, independently of later parent catalog changes.

Backend execution restricts policy-hostable compiled and execution-bound LLM tools to the enabled-tool snapshot before merging bundled client defaults, persisted declarations, and the chat's discovered tools. Declared names override defaults; discoveries override both and retain their supplying channel. Creation reserves server names and composition rejects collisions. Pinned plugin catalogs join the selected server tools independently of compiled preferences. Their names cannot be replaced by client declarations or discovery. Few-shot filtering happens after the final merge.

## Safe changes

- Bind runtime tools in DI and include them in `RuntimeToolsFactory` together; verify invocation through the backend's composed catalogs.
- Add an LLM-dependent tool to the canonical name set and concrete catalog together.
- Use `AgentToolCatalog.filter` and `mapTools` for execution-specific selection and transformations; each returns an immutable catalog without changing its source.

## Verification

Run `./gradlew :agent:test :sharedLogic:test :backend:test`. Cover duplicate detection, category and source order, transformations, immutability, and backend catalog contents.
