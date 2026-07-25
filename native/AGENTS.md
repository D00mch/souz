# Native local runtime

Before changing this module, read its [pain-point index](docs/pain-points.md) and the topics relevant to the change.

## Purpose and boundaries

- `:native` owns the JVM local-model provider, model/asset lifecycle, prompt rendering, strict tool output handling, and the thin `llama.cpp` bridge.
- It depends on `:llms` for shared contracts. Keep UI, host DI, and desktop packaging orchestration outside this module.
- Bridge sources and tracked macOS bridge resources belong here; the desktop build consumes those packaged resources.

## Invariants

- Keep vendor checkouts and bridge build directories untracked. Rebuild tracked bridge binaries through the canonical script.
- Treat the main model, linked embeddings model, and any required multimodal projector as one readiness/download set.
- Cap configured context by the selected profile and reserve completion space from actual token counts; multimodal requests must use the `mtmd` prompt/image count.
- Keep Metal residency disabled by default on macOS; opt back in only through the documented debugging override.

## Verification

For Kotlin runtime changes:

```bash
./gradlew :native:test
```

For bridge source or ABI changes, rebuild both packaged macOS binaries first:

```bash
desktopApp/src/main/resources/scripts/build-llama-bridge.sh
```
