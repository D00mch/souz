# LLMs

Before changing this module, read the relevant pain-point topics below.

## Purpose and boundaries

- `:llms` owns provider-neutral chat, embeddings, tool-call, model, token-accounting, build-profile, JSON, and state-path contracts shared by the runtime modules.
- Keep provider implementations, local inference, UI, and application wiring in their owning modules.
- This module must not depend on `:sharedUI`, `:desktopApp`, or `:native`; local-model availability crosses the boundary through `LocalModelAvailability`.

## Invariants

- Keep wire DTOs and enum aliases backward compatible unless all persisted and remote consumers are migrated together.
- Preserve provider neutrality in public contracts; provider-specific transport behavior belongs in the provider implementation.
- Keep model-default and availability decisions in build profiles, with host capabilities supplied through narrow interfaces.
- Preserve the shared Souz state-directory contract because multiple modules resolve persisted assets through it.

## Pain points

- [Model resolution](docs/model-resolution.md) — provider-neutral embedding defaults, normalized aliases, ambiguity, and unsupported providers.

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

```bash
./gradlew :llms:test
```
