# LLMs

## Module Scope

- Shared `ru.souz.llms` contracts live here: DTOs, model/provider enums, request/response helpers, token logging, and build-profile selection logic.
- This module should stay independent from `composeApp` and `native`.
- Local-model availability is consumed through `LocalModelAvailability` so `LlmBuildProfile` can stay in this shared module without depending on the JVM local runtime implementation.