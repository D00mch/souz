# Gigadesk

## General Info
Gigadesk is a Kotlin Multiplatform desktop AI assistant built with Compose for Desktop.
The repository is a multi-module Gradle project:

- `:composeApp` - main desktop app (UI, agent runtime, tools, integrations).
- `:proto` - protobuf/gRPC generation module consumed by the app.

Primary stack:
- Kotlin + Compose Multiplatform
- Ktor (client and local server)
- gRPC/Protobuf
- JUnit 5 + MockK for testing

### UI architecture principles

- UI layers (Screens and Composables) should not do neither business logic, nor IO operations.
- UI-logic should be coordinated from ViewModels. ViewModel may delegate business logic to UseCases.

### Features
- **Graph-based agent runtime** with explicit nodes, transitions, retries, and session history.
- **Multi-model LLM integrations** for GigaChat (REST/gRPC/voice), Qwen, AiTunnel APIs.
- **MCP integration** over `stdio` and `http` with OAuth discovery and token refresh support.
- **Rich desktop toolset**: files, browser, calendar, mail, notes, desktop automation, analytics, and presentations.
- **Voice and desktop interaction** via audio recording/playback, global hotkeys, and native media key bindings.
- **Local server mode** to expose agent endpoints for local integrations/companion clients.

## Project Structure
```text
.
├── composeApp/                         # Main desktop application module
│   ├── build/                          # Build output for composeApp (generated)
│   ├── composeApp/                     # Auxiliary nested folder with test resource skeleton
│   │   └── src/
│   │       └── jvmTest/
│   │           └── resources/
│   │               └── directory/      # Placeholder fixture directory
│   └── src/
│       ├── jvmMain/                    # Production JVM sources/resources
│       │   ├── composeResources/       # Compose Multiplatform resources
│       │   │   └── drawable/           # Application icons and drawable assets
│       │   ├── kotlin/
│       │   │   └── ru/gigadesk/        # Application Kotlin code
│       │   │       ├── agent/          # Graph-based agent assembly. The main agent related logic is here
│       │   │       │   ├── engine/     # Core graph primitives (Node, Graph, runner/runtime)
│       │   │       │   ├── nodes/      # Graph node implementations (LLM, MCP, classification, etc.)
│       │   │       │   └── session/    # Graph session models, repository, and service
│       │   │       ├── anthropic/      # Anthropic API integration (not supported curerntly)
│       │   │       ├── audio/          # Audio capture/playback utilities
│       │   │       ├── db/             # Local config/data extraction/vector DB layer
│       │   │       ├── di/             # Dependency wiring (DI container setup)
│       │   │       ├── giga/           # GigaChat auth/chat/voice clients and model factory
│       │   │       ├── image/          # Image utility helpers
│       │   │       ├── keys/           # Keyboard listeners and key automation
│       │   │       ├── libs/           # Native library bridge wrappers
│       │   │       ├── llms/           # Additional LLM provider clients
│       │   │       ├── mcp/            # MCP sessions, transport, config, OAuth, protocol adapter
│       │   │       ├── permissions/    # Permission/relaunch helpers
│       │   │       ├── server/         # Local server endpoints and API models
│       │   │       ├── tool/           # Tool framework and concrete tool implementations
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
│       │   │       │   └── textReplace/    # Clipboard and selected-text tools
│       │   │       └── ui/             # Compose UI layer
│       │   │           ├── common/     # Shared UI utilities/components
│       │   │           ├── components/ # Reusable UI widgets
│       │   │           ├── graphlog/   # Graph sessions visualization screens
│       │   │           ├── main/       # Main chat screen/view-model. Agent interaction happens here
│       │   │           │   └── usecases/   # Main flow use cases (chat, speech, onboarding)
│       │   │           ├── settings/   # Settings screens and view-models
│       │   │           ├── setup/      # First-run setup flow
│       │   │           └── tools/      # Tool management/detail screens
│       │   ├── proto/                  # Proto source files used for gRPC generation
│       │   │   └── gigachat/v1/        # GigaChat API protobuf schema
│       │   ├── resources/              # Runtime resources
│       │   │   ├── certs/              # Trusted certificate bundles
│       │   │   ├── darwin-arm64/       # macOS arm64 JNI/native binaries
│       │   │   └── scripts/            # Helper scripts and native build helpers
│       │   └── swift/                  # Swift source for native media keys bridge
│       └── jvmTest/                    # JVM test source set
│           ├── kotlin/                 # Unit/integration tests by feature domain
│           │   ├── agent/              # Agent scenario/integration tests
│           │   ├── classification/     # Classification prompt tests
│           │   ├── db/                 # Data/vector DB tests
│           │   ├── giga/               # Giga API/tool tests
│           │   ├── ru/gigadesk/        # Package-aligned tests
│           │   │   ├── tool/           # Tool tests in package namespace
│           │   │   └── ui/             # UI/view-model tests
│           │   ├── server/             # Local server tests
│           │   └── tool/files/         # File-tool focused tests
│           └── resources/
│               └── directory/          # File fixture directory for tests
├── dest/                               # Local output/scratch directory (currently empty)
├── gradle/                             # Gradle version catalog and wrapper configuration
│   └── wrapper/                        # Gradle wrapper JAR/properties
└── proto/                              # Protobuf/gRPC generation module
    └── build/                          # Generated classes/stubs/artifacts (generated)
```

Notes:
- Directories like `.gradle/`, `.idea/`, `.kotlin/`, and `*/build/` are generated/local environment folders.
- The `:proto` module reads `.proto` files from `composeApp/src/jvmMain/proto`.
