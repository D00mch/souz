# LLMs

Before changing this module, read the relevant pain-point topics below.

## Purpose and boundaries

- `:llms` owns provider-neutral chat, embeddings, tool-call, model, token-accounting, regional model-default, JSON, and state-path contracts shared by the runtime modules.
- Keep provider implementations and application wiring in their owning modules. This module has no production project dependencies.

## Invariants

- Keep wire DTOs and enum aliases backward compatible unless all persisted and remote consumers are migrated together.
- Preserve provider neutrality in public contracts; provider-specific transport behavior belongs in the provider implementation.
- Preserve regional cloud-model defaults and provider priorities when changing model selection.
- Preserve the shared Souz state-directory contract because multiple modules resolve persisted assets through it.

## Pain points

- [Model resolution](docs/model-resolution.md) — provider-neutral embedding defaults, normalized aliases, ambiguity, and unsupported providers.

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

```bash
./gradlew :llms:test
```
