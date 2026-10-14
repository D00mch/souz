# Tool catalog composition

## Invariants

`LlmBackedToolCatalog` is the single concrete catalog for `InternetSearch`, `InternetResearch`, `ViewImage`, and `GenerateImage`. Portable and JVM runtime catalogs do not contain those tools. Catalogs iterate `ToolCategory.entries`, reject duplicate names by default, and return immutable snapshots.

Backend execution merges compiled and execution-bound LLM tools before applying the enabled-tool snapshot. Persisted client tools merge afterward; creation reserves server names and composition rejects collisions. Few-shot filtering happens after the final merge.

## Safe changes

- Bind portable tools in DI and include them in `PortableRuntimeToolsFactory` together. Desktop maintains separate file-tool bindings in its composition root; verify invocation through host DI catalogs.
- Add an LLM-dependent tool to the canonical name set and concrete catalog together.
- Use explicit later-source precedence only where the owning host defines why the later source wins.
- Apply request filtering and function transformations to a copied execution snapshot.

## Verification

Run `./gradlew :sharedLogic:jvmTest :backend:test :desktopApp:test`. Cover duplicate detection, category order, precedence, immutability, and host catalog contents.
