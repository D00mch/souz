# Runtime

The `:runtime` module contains shared JVM runtime pieces used by both desktop (`:composeApp`) and backend (`:backend`):

- secure config/settings access (`ConfigStore`, `SettingsProvider`, `SettingsProviderImpl`);
- provider chat clients (Giga, Qwen, AiTunnel, Anthropic, OpenAI);
- shared LLM routing/classification (`LLMFactory`, `ApiClassifier`);
- shared tool contracts/adapters plus the backend-safe tool catalog (`files`, `web`, `calculator`, `data analytics`, and non-UI config tools), now with invocation-time sandbox resolution from `ToolInvocationMeta` so singleton tools can stay stateless across desktop and backend hosts;
- production skills infrastructure: safe bundle filesystem access, per-user bundle persistence under `~/.local/state/souz/skills/`, and separate file-backed validation cache storage under `~/.local/state/souz/skill-validations/`;
- shared runtime sandbox adapters under `runtime/sandbox/` for host-local and Docker-backed filesystem and process isolation, plus factory-based mode selection via `SOUZ_SANDBOX_MODE=local|docker`;
- shared Kodein DI modules for JVM runtime settings/local-model services and provider/LLM wiring reused by both desktop and backend;
- runtime resources required by shared clients (for example Giga trust certificates).

## Project Structure

```text
runtime/
├── build.gradle.kts                              # JVM module build and shared runtime dependencies
├── AGENTS.md                                     # Module notes and structure
└── src/
    └── main/
        ├── kotlin/
        │   └── ru/souz/
        │       ├── db/
        │       │   ├── ConfigStore.kt            # Secure config persistence and lookup
        │       │   └── SettingsProvider.kt       # Runtime settings access contract + implementation
        │       ├── llms/
        │       │   ├── anthropic/                # Anthropic API client and Ktor defaults
        │       │   ├── giga/                     # Giga auth, HTTP client defaults, and chat API
        │       │   ├── openai/                   # OpenAI chat API client
        │       │   ├── qwen/                     # Qwen chat API client
        │       │   ├── runtime/                  # Shared model routing/classification helpers
        │       │   └── tunnel/                   # AiTunnel chat API client
        │       ├── skills/
        │       │   ├── bundle/                   # Load SkillBundle (how bytes become a SkillBundle)
        │       │   ├── filesystem/               # Safe/replaceable filesystem access (host/user/sandbox)
        │       │   ├── registry/                 # Store and load user skill bundles
        │       │   └── validation/               # Store and load validation records
        │       ├── runtime/
        │       │   ├── di/                       # Shared Kodein modules for JVM runtime/core LLM wiring
        │       │   └── sandbox/                  # Replaceable local/Docker sandbox filesystem + command execution
        │       ├── service/
        │       │   └── files/                    # Shared JVM file service implementations
        │       └── tool/
        │           ├── RuntimeToolsModule.kt     # DI wiring for backend-safe runtime tools
        │           ├── ToolPermissionBroker.kt   # Shared tool permission contract
        │           ├── ToolSetup.kt              # Shared tool setup helpers/adapters
        │           ├── config/                   # Non-UI config tools
        │           ├── dataAnalytics/            # CSV plotting and spreadsheet helpers
        │           ├── files/                    # File read/write/search/move/extract tools
        │           ├── math/                     # Calculator tool
        │           └── web/                      # Search/research/page/image web tools + internals
        └── resources/
            └── certs/                            # Bundled Russian trust certs for provider clients
```

## Notes

- `:runtime` is JVM-only.
- Backend no longer depends on `:composeApp`; both backend and desktop reuse these classes from `:runtime`.
- `RuntimeSandboxFactory` is the entry point for selecting local vs Docker sandboxes. Desktop may pin one singleton sandbox for `SandboxScope.localDefault()`, while backend resolves per-user sandboxes from `ToolInvocationMeta.userId`.
