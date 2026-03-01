# Souz

Keep this file updated whenever top level details changes.
If you are not sure about something, left a note for other developers to review. 

## General Info
Souz is a Kotlin Multiplatform desktop AI assistant built with Compose for Desktop.
The repository is a Gradle project centered around:

- `:composeApp` - main desktop app (UI, agent runtime, tools, integrations).

Primary stack:
- Kotlin + Compose Multiplatform
- Ktor (client)
- JUnit 5 + MockK for testing

### UI architecture principles

- UI layers (Screens and Composables) should not do neither business logic, nor IO operations.
- UI-logic should be coordinated from ViewModels. ViewModel may delegate business logic to UseCases.

### Features
- **Graph-based agent runtime** with explicit nodes, transitions, retries, and session history.
- **Multi-model LLM integrations** for GigaChat (REST/voice), Qwen, AiTunnel, Anthropic Claude, and OpenAI APIs.
- **Edition-aware builds** (`ru`/`en`) with build-profile based provider/model availability and packaging metadata.
- **Key-aware model selection in Settings**: chat and embeddings model lists are filtered by configured provider keys; invalid saved selections are normalized to available providers.
- **MCP integration** over `stdio` and `http` with OAuth discovery and token refresh support.
- **Rich desktop toolset**: files, browser, calendar, mail, notes, desktop automation, analytics, and presentations.
- **Presentation workflow upgrades**: `PresentationCreate` now supports `HTML_FIRST` rendering (default), accepts remote image URLs in `imagePath` (auto-downloads), writes an HTML storyboard preview next to `.pptx`, auto-infers theme/design from topic text when not explicitly provided, validates/normalizes downloaded images before PPTX insertion, adapts PPTX title/body sizing so empty image cards are not rendered for unsupported assets, uses palette-aware HTML-first slide variants instead of one fixed PPTX composition skeleton, detects playful decks to switch into a softer `PLAYFUL` layout/palette instead of reusing the business template with neon custom colors, ships 16 distinct HTML-first composition templates (4 consulting, 4 dark-tech, 4 editorial, 4 playful) selected automatically per slide/deck tone, and now renders HTML-first decks without reapplying the old `PresentationDesignSystem` overlay so title slides, text-only slides, and humorous decks get dedicated hero/text-only compositions instead of the same repeating background treatment.
- **Safer file editing**: `EditFile` now replaces only when `oldText` matches exactly once and writes via temp-file swap (atomic when supported) to reduce partial-write risk.
- **Voice and desktop interaction** via audio recording/playback, global hotkeys, and native media key bindings.
- **Telegram PC Control bot**: automated bot creation via `@BotFather`, long-polling command listener, and agent-driven responses — all managed from the Telegram settings screen. Bot credentials (`TG_BOT_TOKEN`, `TG_BOT_OWNER_ID`, `TG_BOT_USERNAME`) are stored in `ConfigStore`. The bot can be created/deleted from the UI; on creation it automatically sends `/start` and sets a profile avatar. Telegram integration is runtime-gated on macOS and disabled on versions below macOS 15 (with UI/tool warnings instead of app crash). Telegram tool category is also disabled while Telegram auth state is not `READY`.
- **Model-aware speech recognition routing**: voice input recognition can use SaluteSpeech, OpenAI transcription (`/v1/audio/transcriptions`), or AiTunnel transcription (`/v1/audio/transcriptions`, RU edition only), and selects provider based on active model provider and configured keys.

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
│       │   │   └── ru/souz/        # Application Kotlin code
│       │   │       ├── agent/          # Graph-based agent assembly. The main agent related logic is here
│       │   │       │   ├── engine/     # Core graph primitives (Node, Graph, runner/runtime)
│       │   │       │   ├── nodes/      # Graph node implementations (LLM, MCP, classification, etc.)
│       │   │       │   └── session/    # Graph session models, repository, and service
│       │   │       ├── audio/          # Audio capture/playback utilities
│       │   │       ├── db/             # Local config/data extraction/vector DB layer
│       │   │       ├── di/             # Dependency wiring (DI container setup)
│       │   │       ├── edition/        # Runtime build edition parsing/config (RU/EN)
│       │   │       ├── giga/           # GigaChat auth/chat/voice clients and edition-aware model profile
│       │   │       ├── image/          # Image utility helpers
│       │   │       ├── keys/           # Keyboard listeners and key automation
│       │   │       ├── libs/           # Native library bridge wrappers
│       │   │       ├── llms/           # Additional LLM provider clients (Qwen, AiTunnel, Anthropic)
│       │   │       ├── mcp/            # MCP sessions, transport, config, OAuth, protocol adapter
│       │   │       ├── permissions/    # Permission/relaunch helpers
│       │   │       ├── service/       # Service-layer integrations
│       │   │       │   └── telegram/  # Telegram client (TdLib) + Bot polling controller
│       │   │       │       ├── TelegramService.kt       # TdLib wrapper: auth, messaging, bot creation via BotFather
│       │   │       │       └── TelegramBotController.kt  # Long-polling listener for the PC Control bot
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
|       |   |           |   └── INFO.md # The same file as this one, with the details on ui/main
│       │   │           ├── settings/   # Settings screens and view-models
│       │   │           ├── setup/      # First-run setup flow
│       │   │           └── tools/      # Tool management/detail screens
│       │   ├── resources/              # Runtime resources
│       │   │   ├── bot_avatar.png      # Default avatar image for the Telegram PC Control bot
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
│           │   ├── ru/souz/        # Package-aligned tests
│           │   │   ├── tool/           # Tool tests in package namespace
│           │   │   └── ui/             # UI/view-model tests
│           │   └── tool/files/         # File-tool focused tests
│           └── resources/
│               └── directory/          # File fixture directory for tests
├── dest/                               # Local output/scratch directory (currently empty)
├── build-logic/                        # Included Gradle build with convention plugins/shared build logic
├── gradle/                             # Gradle version catalog and wrapper configuration
│   └── wrapper/                        # Gradle wrapper JAR/properties
```

Notes:
- Directories like `.gradle/`, `.idea/`, `.kotlin/`, and `*/build/` are generated/local environment folders.
- `build-logic` provides convention plugins for shared Gradle behavior, including mac signing/notarization and compose-app native resource/packaging wiring.
- `ComposeAppConventionsPlugin` runs `:composeApp:patchReleaseAppForNotarization` before DMG/PKG packaging/notarization to deep re-sign and verify the final `.app` bundle after release app assembly.
- macOS signing config is now split by build mode: App Store builds (`-PmacOsAppStoreRelease=true`) use provisioning profiles + sandbox entitlements, while Developer ID DMG builds use non-App-Store entitlements and do not embed provisioning profiles.
- macOS runtime image explicitly includes `java.net.http` so release app bundles contain `java.net.http.HttpClient` used by Telegram service startup.
- Voice recognition audio upload now sends raw PCM (`audio/x-pcm;bit=16;rate=16000`) directly to Salute Speech, so the app no longer depends on JAVE/embedded FFmpeg binaries for microphone transcription.
- OpenAI and AiTunnel voice transcription wrap recorded raw PCM (16kHz mono 16-bit) into a WAV container before multipart upload (`capture.wav`, `audio/wav`) because these transcription endpoints do not accept the recorder's raw PCM stream directly.
- `ConfigStore` now encrypts sensitive values (LLM API keys, Telegram bot token, MCP OAuth state, `MCP_SERVERS_JSON`) before writing to Java Preferences using AES-GCM + PBKDF2. `SOUZ_MASTER_KEY` (env var or JVM system property) can be used as an override; otherwise the app auto-generates and stores a local master key file in the user profile (platform-specific app config directory). Legacy plaintext values are read and transparently migrated to encrypted storage.
