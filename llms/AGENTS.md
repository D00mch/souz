# LLMs

## Purpose and boundaries

- `:llms` owns provider-neutral LLM and shared runtime contracts. Keep provider implementations and application wiring in their owning modules.

## Invariants

- Keep wire DTOs and enum aliases backward compatible unless all persisted and remote consumers are migrated together.
- Preserve [regional model defaults and provider priorities](src/main/kotlin/ru/souz/llms/LlmBuildProfile.kt) and the [shared state-directory contract](src/main/kotlin/ru/souz/paths/SouzPaths.kt).

## Pain points

- [Model resolution](docs/model-resolution.md) — provider-neutral embedding defaults, normalized aliases, ambiguity, and unsupported providers.

## Verification

Run `./gradlew :llms:test`.
