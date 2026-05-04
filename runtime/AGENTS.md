# Runtime

The `:runtime` module contains shared JVM runtime infrastructure reused by both desktop (`:composeApp`) and backend (`:backend`).

Keep this module UI-free and safe to use from backend code (stateless).

## Responsibilities

- Provider clients and shared LLM runtime wiring.
- Settings and config access.
- Runtime-safe tool implementations.
- Skill bundle loading, storage, filesystem access, and validation storage.
- Sandbox-aware filesystem and command execution contracts.
- Shared Kodein DI modules for runtime wiring.

## Project Structure

```text
runtime/
├── build.gradle.kts
├── AGENTS.md
└── src/
    └── main/
        ├── kotlin/
        │   └── ru/souz/
        │       ├── db/                       # ConfigStore and SettingsProvider
        │       ├── llms/                     # Provider clients and LLM runtime helpers
        │       ├── skills/
        │       │   ├── bundle/               # Load SkillBundle: how bytes/files become a SkillBundle
        │       │   ├── filesystem/           # Safe/replaceable filesystem access: host/user/sandbox
        │       │   ├── registry/             # Store and load installed skill bundles
        │       │   └── validation/           # Store and load skill validation records
        │       ├── runtime/
        │       │   ├── di/                   # Shared runtime DI modules
        │       │   └── sandbox/              # Local/Docker sandbox abstractions
        │       ├── service/
        │       │   └── files/                # Shared JVM file services
        │       └── tool/
        │           ├── RuntimeToolsModule.kt # Backend-safe tool catalog wiring
        │           ├── config/               # Non-UI config tools
        │           ├── dataAnalytics/        # CSV/Excel/data helpers
        │           ├── files/                # File tools
        │           ├── math/                 # Calculator tool
        │           └── web/                  # Web/search/research tools
        └── resources/
            └── certs/                        # Runtime provider certificates
````

## Notes

* `:runtime` is JVM-only.
* `RuntimeSandboxFactory` selects sandbox mode with `SOUZ_SANDBOX_MODE=local|docker`.
* Local mode is the default when `SOUZ_SANDBOX_MODE` is unset.
* Tools should resolve sandbox/filesystem access per invocation from `ToolInvocationMeta`, not cache user-specific paths in singleton tools.
* Skill bundle loading should stay split:
    * `skills/bundle/` decides how validated files become a `SkillBundle`.
    * `skills/filesystem/` owns safe, replaceable filesystem access for host/user/sandbox environments.
* Skill storage and validation repositories resolve `SouzPaths` through `SandboxFileSystem`.
* Avoid direct host filesystem access in new skill/tool code when sandbox-aware abstractions are available.
