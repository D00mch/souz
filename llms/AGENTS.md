# LLMs

## Purpose and boundaries

- `:llms` owns provider-neutral LLM and shared runtime contracts. Keep provider implementations, local inference, UI and application wiring in their owning modules. Local-model availability crosses the boundary through `LocalModelAvailability`; use the shared chat catalog with host-supplied capabilities.

## Invariants

- Keep wire DTOs and enum aliases backward compatible unless all persisted and remote consumers are migrated together.
- Preserve [chat model defaults and availability](src/main/kotlin/ru/souz/llms/ChatModelCatalog.kt) and the [shared state-directory contract](src/main/kotlin/ru/souz/paths/SouzPaths.kt).

## Pain points

- [Model resolution](docs/model-resolution.md) — provider-neutral embedding defaults, normalized aliases, ambiguity, and unsupported providers.

## Verification

Run `./gradlew :llms:test`.
