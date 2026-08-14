# Tool catalog composition

## Invariants

`LlmBackedToolCatalog` is the single concrete catalog for `InternetSearch`, `InternetResearch`, `ViewImage`, and `GenerateImage`. Portable and JVM runtime catalogs do not contain those tools. Catalogs iterate `ToolCategory.entries`, reject duplicate names by default, and return immutable snapshots.

Backend execution merges compiled and execution-bound LLM tools before applying the enabled-tool snapshot. Client tools merge afterward and intentionally replace a same-named compiled tool because the live client owns that execution boundary. Few-shot filtering happens after the final merge.

## Safe changes

- Add an LLM-dependent tool to the canonical name set and concrete catalog together.
- Use explicit later-source precedence only where the owning host defines why the later source wins.
- Apply request filtering and function transformations to a copied execution snapshot.

## Verification

Run `./gradlew :sharedLogic:jvmTest :backend:test :desktopApp:test`. Cover duplicate detection, category order, precedence, immutability, and host catalog contents.
