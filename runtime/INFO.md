# Runtime

The `:runtime` module contains shared JVM runtime pieces used by both desktop (`:composeApp`) and backend (`:backend`):

- secure config/settings access (`ConfigStore`, `SettingsProvider`, `SettingsProviderImpl`);
- provider chat clients (Giga, Qwen, AiTunnel, Anthropic, OpenAI);
- shared LLM routing/classification (`LLMFactory`, `ApiClassifier`);
- shared tool contracts/adapters plus the backend-safe tool catalog (`files`, `web`, `calculator`, `data analytics`, and non-UI config tools);
- runtime resources required by shared clients (for example Giga trust certificates).

## Project Structure

```text
runtime/
├── build.gradle.kts                               # JVM module build and shared runtime dependencies
├── INFO.md                                        # Module notes and structure
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
