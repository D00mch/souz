# Souz

Souz is a Kotlin Multiplatform desktop AI assistant built with Compose for Desktop.

## Note for LLM

Keep this file updated whenever top level details changes.
If you are not sure about something, left a note for other developers to review.

### UI architecture principles

- UI layers (Screens and Composables) should not do neither business logic, nor IO operations.
- UI-logic should be coordinated from ViewModels. ViewModel may delegate business logic to UseCases.

### Development principles

- Prefer composition to inheritance. 
- Do not mix coroutines with the JVM low level concurrency primitives such as: Volatile, Synchronize, ThreadLocal, etc).
- Utilize open closed principle.

## Features
- **Graph-based agent runtime** with explicit nodes, transitions, retries, and session history.
- **Multi-model LLM integrations** for GigaChat (REST/voice), Qwen, AiTunnel, Anthropic Claude, and OpenAI APIs.
- **Local llama.cpp provider** with a thin native bridge, strict JSON tool contract, a RAM-gated local model catalog (currently Qwen profile), background preload/warmup on local model selection, prompt-prefix/KV reuse inside the native runtime, and model storage under `~/.local/state/souz/models/`.
- **Runtime EN/RU profile toggle** with one packaged build: profile is switched via a shared segmented RU/EN toggle in Setup and Settings and controls provider/model availability.
- **Key-aware model selection in Settings**: chat, embeddings, and voice recognition model lists are filtered by configured provider keys; invalid saved selections are normalized to available providers.
- **MCP integration** over `stdio` and `http` with OAuth discovery and token refresh support.
- **Rich desktop toolset**: files, browser, calendar, mail, notes, desktop automation, analytics, and presentations.
- **Two-mode internet search**: quick-answer web lookup for simple factual questions and multi-step research mode with LLM-built strategy, broader source coverage, cited long-form synthesis, and automatic `.md` export for oversized reports.
- **Voice and desktop interaction** via audio recording/playback, global hotkeys, and native media key bindings.

## Project Structure

```text
.
├── docs/                                   # Project docs extracted from top-level notes
│   ├── config-store-security.md            # ConfigStore encryption and secret handling
│   ├── file-tools.md                       # File tool guarantees and path conventions
│   ├── release.md                          # Release-specific notes
│   ├── telemetry-backend.md                # Telemetry backend contract
│   └── voice-transcription.md              # Voice transcription routing and upload behavior
├── native/                                 # Native bridge sources and local notes
│   ├── INFO.md                             # Local native bridge notes
│   └── llama-bridge/                       # JNI bridge sources and local-only build-* dirs
├── composeApp/                             # Main desktop application module
│   ├── build/                              # Build output for composeApp (generated)
│   ├── composeApp/                         # Auxiliary nested folder with test resource skeleton
│   │   └── src/
│   │       └── jvmTest/
│   │           └── resources/
│   │               └── directory/          # Placeholder fixture directory
│   └── src/
│       ├── jvmMain/                        # Production JVM sources/resources
│       │   ├── composeResources/           # Compose Multiplatform resources
│       │   │   └── drawable/               # Application icons and drawable assets
│       │   ├── kotlin/
│       │   │   └── ru/souz/                # Application Kotlin code
│       │   │       ├── App.kt              # Compose desktop app shell
│       │   │       ├── Main.kt             # JVM entry point
│       │   │       ├── TextMain.kt         # Text-mode/dev entry point
│       │   │       ├── agent/              # Graph-based agent runtime, nodes, execution, and sessions
│       │   │       │   ├── engine/         # Graph primitives and runner/runtime contracts
│       │   │       │   ├── impl/           # Agent implementations (standard and Lua-backed)
│       │   │       │   ├── nodes/          # LLM, MCP, classification, summarization, and error nodes
│       │   │       │   ├── runtime/        # Lua runtime and tool execution delegation
│       │   │       │   └── session/        # Persisted graph session models and services
│       │   │       ├── db/                 # Settings, config store, desktop info, and vector DB layer
│       │   │       ├── di/                 # Dependency wiring
│       │   │       ├── llms/               # Shared LLM abstractions, DTOs, profiles, and provider factory
│       │   │       │   ├── anthropic/      # Anthropic chat client and Ktor defaults
│       │   │       │   ├── giga/           # GigaChat auth, REST chat, tools, and voice clients
│       │   │       │   ├── local/          # Local llama.cpp bridge, model store/catalog, strict JSON support
│       │   │       │   │   └── INFO.md     # Local notes for the local provider
│       │   │       │   ├── openai/         # OpenAI chat and voice clients
│       │   │       │   ├── qwen/           # Qwen chat client
│       │   │       │   └── tunnel/         # AiTunnel chat and voice clients
│       │   │       ├── service/            # Runtime integrations and OS-facing helpers
│       │   │       │   ├── audio/          # Audio recording and playback services
│       │   │       │   ├── files/          # File access service helpers
│       │   │       │   ├── image/          # Image utility service helpers
│       │   │       │   ├── keys/           # Key listener, native, and robot input helpers
│       │   │       │   ├── mcp/            # MCP client/session/config/OAuth/protocol services
│       │   │       │   ├── permissions/    # Relaunch and macOS permission helpers
│       │   │       │   ├── telegram/       # Telegram service, auth bridge, bot workflows, and lookup
│       │   │       │   │   └── INFO.md     # Local notes for service/telegram package
│       │   │       │   └── telemetry/      # Telemetry crypto, outbox storage, runtime config, and delivery
│       │   │       │       └── INFO.md     # Local notes for service/telemetry package
│       │   │       ├── tool/               # Tool registry, permissions, classifiers, and implementations
│       │   │       │   ├── application/    # App launch and listing tools
│       │   │       │   ├── browser/        # Browser control, hotkeys, and tab/page tools
│       │   │       │   ├── calendar/       # Calendar list/create/delete tools
│       │   │       │   ├── config/         # Runtime sound and instruction config tools
│       │   │       │   ├── dataAnalytics/  # CSV analytics and plotting tools
│       │   │       │   │   └── excel/      # Excel ingestion/report helpers
│       │   │       │   ├── desktop/        # Desktop automation (windows, mouse, screenshots, media)
│       │   │       │   ├── files/          # File discovery/read/modify/extract tools
│       │   │       │   ├── mail/           # Mail search/read/send/reply tools
│       │   │       │   ├── math/           # Calculator tool
│       │   │       │   ├── notes/          # Notes CRUD/search tools
│       │   │       │   ├── presentation/   # Presentation create/read/theme helpers
│       │   │       │   ├── telegram/       # Telegram messaging, inbox, history, selection, and approval
│       │   │       │   │   └── INFO.md     # Local notes for tool/telegram package
│       │   │       │   ├── textReplace/    # Clipboard and selected-text tools
│       │   │       │   └── web/            # Internet search, research, page text, and image search tools
│       │   │       │       └── internal/   # Web execution, parsing, and report-formatting internals
│       │   │       └── ui/                 # Compose UI screens, view-model base classes, and components
│       │   │           ├── common/         # Shared UI helpers, dialogs, links, and profile toggle
│       │   │           │   └── usecases/   # Shared UI use cases
│       │   │           ├── components/     # Reusable Compose widgets
│       │   │           ├── graphlog/       # Graph sessions and visualization screens
│       │   │           ├── macos/          # macOS-specific window presentation helpers
│       │   │           ├── main/           # Main chat screen, view-model, thinking panel, and attachments
│       │   │           │   ├── usecases/   # Main chat, speech, permissions, and attachments use cases
│       │   │           │   └── INFO.md     # Local notes for ui/main package
│       │   │           ├── settings/       # Settings screens, model availability, and support flows
│       │   │           │   └── INFO.md     # Local notes for ui/settings package
│       │   │           ├── setup/          # First-run setup flow
│       │   │           └── tools/          # Tool catalog, detail, and settings screens
│       │   ├── resources/                  # Runtime resources
│       │   │   ├── bot_avatar.png          # Default avatar image for the Telegram PC Control bot
│       │   │   ├── certs/                  # Trusted certificate bundles
│       │   │   ├── darwin-arm64/           # macOS arm64 JNI/native binaries
│       │   │   └── scripts/                # Helper scripts and native build helpers
│       │   └── swift/                      # Swift source for native media keys bridge
│       └── jvmTest/                        # JVM test source set
│           ├── kotlin/                     # Unit/integration tests by feature domain
│           │   ├── agent/                  # Agent scenario/integration tests
│           │   ├── classification/         # Classification prompt tests
│           │   ├── db/                     # Data/vector DB tests
│           │   ├── giga/                   # Giga API/tool tests
│           │   ├── ru/souz/                # Package-aligned tests
│           │   │   ├── tool/               # Tool tests in package namespace
│           │   │   └── ui/                 # UI/view-model tests
│           │   └── tool/files/             # File-tool focused tests
│           └── resources/
│               └── directory/              # File fixture directory for tests
├── dest/                                   # Local output/scratch directory (currently empty)
├── build-logic/                            # Included Gradle build with convention plugins/shared build logic
├── gradle/                                 # Gradle version catalog and wrapper configuration
│   └── wrapper/                            # Gradle wrapper JAR/properties
```
