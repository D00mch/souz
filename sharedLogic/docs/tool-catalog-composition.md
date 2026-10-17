# Tool catalog composition

## Invariants

`AgentToolCatalog` in `:agent` owns immutable tool membership and name indexing. Construction, filtering, transformation, and composition preserve `ToolCategory.entries` order and reject duplicate function names. Tool instances retain their invocation behavior; catalog immutability applies to the outer and inner maps.

Host factories expose their catalog through `.catalog`. `LlmBackedToolCatalog` is the single factory for `InternetSearch`, `InternetResearch`, `ViewImage`, and `GenerateImage`. Portable and JVM runtime catalogs do not contain those tools.

Skill lookup, invocation, and subagent selection may read a host-supplied catalog provider. Each lookup or spawn selects one immutable snapshot; children retain only the capabilities selected at spawn, independently of later parent catalog changes.

Backend execution restricts policy-hostable compiled and execution-bound LLM tools to the enabled-tool snapshot before adding bundled client defaults merged by name with persisted declarations; declared names override matching defaults. Creation reserves server names and composition rejects remaining collisions. Few-shot filtering happens after the final merge.

## Safe changes

- Bind portable tools in DI and include them in `PortableRuntimeToolsFactory` together. Desktop maintains separate file-tool bindings in its composition root; verify invocation through host DI catalogs.
- Add an LLM-dependent tool to the canonical name set and concrete catalog together.
- Use `AgentToolCatalog.filter` and `mapTools` for execution-specific selection and transformations; each returns an immutable catalog without changing its source.

## Verification

Run `./gradlew :agent:test :sharedLogic:jvmTest :backend:test :desktopApp:test`. Cover duplicate detection, category and source order, transformations, immutability, and host catalog contents.
