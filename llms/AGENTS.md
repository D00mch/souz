# LLMs

## Module Scope

- Shared `ru.souz.llms` contracts live here: DTOs, model/provider enums, request/response helpers, token logging, build-profile selection logic, and the shared Souz state-path helper consumed by `:agent`, `:runtime`, and `:native`.
- This module should stay independent from `composeApp` and `native`.
- Local-model availability is consumed through `LocalModelAvailability` so `LlmBuildProfile` can stay in this shared module without depending on the JVM local runtime implementation.

## Project Structure

```text
llms/
├── src/main/kotlin/ru/souz/llms/
│   ├── BuildEdition.kt                # RU/EN build edition enum used by model defaults
│   ├── DTO.kt                         # Shared request/response DTOs, model enums, providers, embeddings, and roles
│   ├── LLMChatAPI.kt                  # Provider-agnostic chat, streaming, embeddings, file, and balance API contract
│   ├── LLMToolSetup.kt                # Shared tool-call setup contract and REST JSON mapper
│   ├── LlmBuildProfile.kt             # Region-aware provider/model defaults, priorities, and local-model gating
│   ├── LlmBuildProfileSupport.kt      # Settings and local-model availability interfaces consumed by build profiles
│   ├── paths/SouzPaths.kt             # Shared ~/.local/state/souz directory layout helper
│   └── TokenLogging.kt                # Token logging contract and session/request token accounting implementation
├── src/test/kotlin/                   # Test source root for shared LLM contract coverage
├── build.gradle.kts                   # Shared LLM contracts module build
├── README.md                          # Public module overview
└── AGENTS.md                          # Local notes for the LLM contracts module
```
