# Native local runtime

Before changing this module, read the relevant pain-point topics below.

## Purpose and boundaries

- `:native` owns the JVM local-model provider, model/asset lifecycle, prompt rendering, strict tool output handling, and the thin `llama.cpp` bridge.
- It depends on `:llms` for shared contracts. Keep UI, host DI, and desktop packaging orchestration outside this module.
- Bridge sources and tracked macOS bridge resources belong here; the desktop build consumes those packaged resources.

## Invariants

- Keep vendor checkouts and bridge build directories untracked. Rebuild tracked bridge binaries through the canonical script.
- Treat the main model, linked embeddings model, and any required multimodal projector as one readiness/download set.
- Cap configured context by the selected profile and reserve completion space from actual token counts; multimodal requests must use the `mtmd` prompt/image count.
- Keep Metal residency disabled by default on macOS; opt back in only through the documented debugging override.

## Pain points

- [Bridge build and packaging](docs/bridge-build-and-packaging.md) — vendor sources, local patches, ABI synchronization, packaged binaries, and Metal defaults.
- [Model assets and context budget](docs/model-assets-and-context-budget.md) — linked downloads, projector resolution, embeddings formatting, and multimodal token accounting.

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

For Kotlin runtime changes:

```bash
./gradlew :native:test
```

For bridge source or ABI changes, rebuild both packaged macOS binaries first:

```bash
desktopApp/src/main/resources/scripts/build-llama-bridge.sh
```
