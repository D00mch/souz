# Shared Logic

The `:sharedLogic` module contains shared JVM runtime infrastructure reused by desktop (`:desktopApp`) and backend (`:backend`).

Keep this module UI-free and backend-safe by default. OS-bound desktop tools/services belong in `:desktopApp`; sharedLogic may expose shared models, contracts, sandbox abstractions, and runtime-safe implementations.

## Responsibilities

- Provider clients and shared LLM runtime wiring.
- Settings and config access.
- Runtime-safe tool implementations.
- Shared MCP, observability, speech provider selection, Telegram models, and Telegram selection broker types.
- Skill bundle loading, storage, filesystem access, and validation storage.
- Sandbox-aware filesystem and command execution contracts.
- Shared Kodein DI modules for runtime wiring.

## Project Structure

```text
sharedLogic/
├── build.gradle.kts
├── Dockerfile                              # Shared local/test Docker sandbox image
├── docker/
│   ├── entrypoint.sh                       # Seeds bundled skills into mounted sandbox state registry
│   └── skills/                             # Docker-bundled development skill fixtures
├── AGENTS.md
└── src/
    └── main/
        ├── kotlin/
        │   └── ru/souz/
        │       ├── db/                       # ConfigStore, SettingsProvider, vector DB
        │       ├── llms/                     # Provider clients, voice APIs, and LLM runtime helpers
        │       ├── skills/
        │       │   ├── bundle/               # Load SkillBundle: how bytes/files become a SkillBundle
        │       │   ├── filesystem/           # Safe/replaceable filesystem access: host/user/sandbox
        │       │   ├── registry/             # Store and load installed skill bundles
        │       │   └── validation/           # Store and load skill validation records
        │       ├── runtime/
        │       │   ├── di/                   # Shared runtime DI modules
        │       │   └── sandbox/              # Local/Docker sandbox abstractions
        │       ├── service/                  # MCP, observability, speech, shared Telegram models/brokers
        │       └── tool/
        │           ├── RuntimeToolsModule.kt # Backend-safe tool catalog wiring
        │           ├── config/               # Non-UI config tools
        │           ├── dataAnalytics/        # CSV/Excel/data helpers
        │           ├── files/                # File tools
        │           ├── math/                 # Calculator tool
        │           ├── presentation/         # Presentation tools
        │           └── web/                  # Web/search/research tools
        └── resources/
            └── certs/                        # Runtime provider certificates
```

## Notes

* `:sharedLogic` is JVM-only.
* OS-bound desktop logic lives in `:desktopApp`, even when it has no Compose/UI dependency. Keep UI resources, composables, and localized UI adapters in `:sharedUI`.
* `RuntimeSandboxFactory` selects sandbox mode with `SOUZ_SANDBOX_MODE=local|docker`.
* Local mode is the default when `SOUZ_SANDBOX_MODE` is unset.
* The runtime Docker image is built from `sharedLogic/Dockerfile`; use `./gradlew :sharedLogic:buildRuntimeSandboxImage` for local app testing.
* Docker-bundled skill files are development fixtures. The entrypoint seeds them into the single-user skill registry storage under `/souz/state/skills/` so desktop/KMP Docker runs can discover them directly.
* Tools should resolve sandbox/filesystem access per invocation from `ToolInvocationMeta`, not cache user-specific paths in singleton tools.
* Skill bundle loading should stay split:
    * `skills/bundle/` decides how validated files become a `SkillBundle`.
    * `skills/filesystem/` owns safe, replaceable filesystem access for host/user/sandbox environments.
* `FileSystemSkillRegistryRepository` owns skill metadata, bundle files, and validation records, resolving `SouzPaths` through `SandboxFileSystem`.
* Avoid direct host filesystem access in new skill/tool code when sandbox-aware abstractions are available.
