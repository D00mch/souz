# LLMs

Before changing this module, read its [pain-point index](docs/pain-points.md) and any relevant topics.

## Purpose and boundaries

- `:llms` owns provider-neutral chat, embeddings, tool-call, model, token-accounting, build-profile, JSON, and state-path contracts shared by the runtime modules.
- Keep provider implementations, local inference, UI, and application wiring in their owning modules.
- This module must not depend on `:sharedUI`, `:desktopApp`, or `:native`; local-model availability crosses the boundary through `LocalModelAvailability`.

## Invariants

- Keep wire DTOs and enum aliases backward compatible unless all persisted and remote consumers are migrated together.
- Preserve provider neutrality in public contracts; provider-specific transport behavior belongs in the provider implementation.
- Keep model-default and availability decisions in build profiles, with host capabilities supplied through narrow interfaces.
- Preserve the shared Souz state-directory contract because multiple modules resolve persisted assets through it.

## Verification

```bash
./gradlew :llms:test
```
