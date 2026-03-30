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
- Do not mix coroutines with the JVM low level concurrency (volatile, synchronize, Threads, ThreadLocal, etc).
- Utilize open closed.

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

## Local Native Bridge

- `third_party/llama.cpp` and `native/llama-bridge/build-*` are local-only paths and should stay untracked.
- Treat those paths as out of scope unless the task is explicitly about updating upstream `llama.cpp` or debugging the native bridge build.
- Packaged bridge binaries live in `composeApp/src/jvmMain/resources/darwin-*`.
- Rebuild the packaged bridge binaries with `composeApp/src/jvmMain/resources/scripts/build-llama-bridge.sh`.
- The rebuild script uses `LLAMA_CPP_SOURCE_DIR` when set, otherwise a local `third_party/llama.cpp` checkout, otherwise it clones the pinned `llama.cpp` ref `968189729f71bf1dbe109556986ddf2e2cf3e534` into `${XDG_CACHE_HOME:-~/.cache}/souz/vendor/llama.cpp`.
- On macOS the bridge now disables ggml Metal residency sets by default (`GGML_METAL_NO_RESIDENCY=1`) to avoid shutdown aborts; set `SOUZ_LLAMA_METAL_RESIDENCY=1` only if you need to opt back in for debugging.

## Project Structure

```text
.
├── docs/                                   # Project docs extracted from top-level notes
│   ├── config-store-security.md            # ConfigStore encryption and secret handling
│   ├── file-tools.md                       # File tool guarantees and path conventions
│   ├── release.md                          # Release-specific notes
│   ├── telemetry-backend.md                # Telemetry backend contract
│   └── voice-transcription.md              # Voice transcription routing and upload behavior
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
│       │   │       ├── agent/              # Graph-based agent assembly. The main agent related logic is here
│       │   │       │   ├── engine/         # Core graph primitives (Node, Graph, runner/runtime)
│       │   │       │   ├── impl/           # Agent implementations: Lua scripts and Function calling
│       │   │       │   ├── runtime/        # LuaRuntime, ToolExecution
│       │   │       │   ├── nodes/          # Graph node implementations (LLM, MCP, classification, etc.)
│       │   │       │   └── session/        # Graph session models, repository, and service
│       │   │       ├── audio/              # Audio capture/playback utilities
│       │   │       ├── db/                 # Local config/data extraction/vector DB layer
│       │   │       ├── di/                 # Dependency wiring (DI container setup)
│       │   │       ├── edition/            # Runtime build edition parsing/config (RU/EN)
│       │   │       ├── giga/               # GigaChat auth/chat/voice clients and edition-aware model profile
│       │   │       ├── image/              # Image utility helpers
│       │   │       ├── keys/               # Keyboard listeners and key automation
│       │   │       ├── libs/               # Native library bridge wrappers
│       │   │       ├── llms/               # Additional LLM provider clients (Qwen, AiTunnel, Anthropic)
│       │   │       ├── mcp/                # MCP sessions, transport, config, OAuth, protocol adapter
│       │   │       ├── permissions/        # Permission/relaunch helpers
│       │   │       ├── service/            # Service-layer integrations
│       │   │       │   └── telegram/       # Telegram client (TdLib) + bot polling/controller workflows
│       │   │       │       └── INFO.md     # Local notes for service/telegram package
│       │   │       ├── telemetry/
│       │   │       ├── tool/               # Tool framework and concrete tool implementations
│       │   │       │   ├── application/    # App launch/list tools
│       │   │       │   ├── browser/        # Browser operations/hotkeys/tab control
│       │   │       │   ├── calendar/       # Calendar list/create/delete tools
│       │   │       │   ├── config/         # Runtime sound/instruction config tools
│       │   │       │   ├── dataAnalytics/  # CSV analytics and plotting
│       │   │       │   │   └── excel/      # Excel read/report helpers
│       │   │       │   ├── desktop/        # Desktop automation (windows, mouse, screenshots, media)
│       │   │       │   ├── files/          # File discovery/read/modify/extract tools
│       │   │       │   ├── mail/           # Mail search/read/send/reply tools
│       │   │       │   ├── math/           # Calculator tool
│       │   │       │   ├── notes/          # Notes CRUD/search tools
│       │   │       │   ├── presentation/   # Presentation create/read/style helpers
│       │   │       │   ├── web/            # Internet search, page fetch, image search/download helpers
│       │   │       │   ├── telegram/       # Telegram messaging/search/inbox tool adapters
│       │   │       │   │   └── INFO.md     # Local notes for tool/telegram package
│       │   │       │   └── textReplace/    # Clipboard and selected-text tools
│       │   │       └── ui/                 # Compose UI layer
│       │   │           ├── common/         # Shared UI utilities/components
│       │   │           ├── components/     # Reusable UI widgets
│       │   │           ├── graphlog/       # Graph sessions visualization screens
│       │   │           ├── main/           # Main chat screen/view-model. Agent interaction happens here
│       │   │           │   ├── usecases/   # Main flow use cases (chat, speech, onboarding)
│       │   │           │   └── INFO.md     # Local notes for ui/main package
│       │   │           ├── settings/       # Settings screens and view-models
│       │   │           │   └── INFO.md     # Local notes for ui/settings package
│       │   │           ├── setup/          # First-run setup flow
│       │   │           └── tools/          # Tool management/detail screens
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
