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
- **Telemetry pipeline with local SQLite outbox**: app usage, chat/conversation usage, tool usage, and token usage are always captured per auto-generated user/device IDs, queued in `~/.local/state/souz/telemetry.db`, and sent batched after installation registration and Ed25519-signed requests.
- **Runtime EN/RU profile toggle** with one packaged build: profile is switched via a shared segmented RU/EN toggle in Setup and Settings and controls provider/model availability.
- **Key-aware model selection in Settings**: chat, embeddings, and voice recognition model lists are filtered by configured provider keys; invalid saved selections are normalized to available providers.
- **MCP integration** over `stdio` and `http` with OAuth discovery and token refresh support.
- **Rich desktop toolset**: files, browser, calendar, mail, notes, desktop automation, analytics, and presentations.
- **Presentation workflow upgrades**: `PresentationCreate` now supports `HTML_FIRST` rendering (default), accepts remote image URLs in `imagePath` (auto-downloads), writes an HTML storyboard preview next to `.pptx`, auto-infers theme/design from topic text when not explicitly provided, validates/normalizes downloaded images before PPTX insertion, adapts PPTX title/body sizing so empty image cards are not rendered for unsupported assets, uses palette-aware HTML-first slide variants instead of one fixed PPTX composition skeleton, detects playful decks to switch into a softer `PLAYFUL` layout/palette instead of reusing the business template with neon custom colors, ships 16 distinct HTML-first composition templates (4 consulting, 4 dark-tech, 4 editorial, 4 playful) selected automatically per slide/deck tone, and now renders HTML-first decks without reapplying the old `PresentationDesignSystem` overlay so title slides, text-only slides, and humorous decks get dedicated hero/text-only compositions instead of the same repeating background treatment.
- **Safer file editing**: `EditFile` now applies unified patches with dry-run validation and feeds patch content directly from memory (no temporary patch files), and in safe mode shows a patch diff preview before apply. On patch errors, tool guidance now explicitly forbids delete+recreate fallback.
- **Centralized Souz file roots**: `FilesToolUtil` now owns canonical user-home/document roots (`~/Documents|documents/souz`, web assets, Telegram control downloads) plus shared local path normalization logic for attachment path extraction.
- **Voice and desktop interaction** via audio recording/playback, global hotkeys, and native media key bindings.
- **Telegram PC Control bot**: automated bot creation via `@BotFather`, long-polling command listener, and agent-driven responses — all managed from the Telegram settings screen. Bot credentials (`TG_BOT_TOKEN`, `TG_BOT_OWNER_ID`, `TG_BOT_USERNAME`) are stored in `ConfigStore`. The bot can be created/deleted from the UI; on creation it automatically sends `/start` and sets a profile avatar. Telegram integration is runtime-gated on macOS and disabled on versions below macOS 15 (with UI/tool warnings instead of app crash). Telegram tool category is also disabled while Telegram auth state is not `READY`.
- **Telegram attachments + voice in control flow**: `ToolTelegramSend` and `ToolTelegramSavedMessages` can send local files as message attachments (auto-detecting file paths that come from Finder-attached chat context), while Telegram Control Bot inbound `document` files are downloaded into `~/Documents/souz/telegram` (or `~/documents/souz/telegram` when that root already exists) and appended to the agent request as local paths; inbound `voice` messages are converted to 16kHz mono PCM, transcribed through the configured speech-recognition provider, and processed as regular text commands.
- **Model-aware speech recognition routing**: voice input recognition can use SaluteSpeech, OpenAI transcription (`/v1/audio/transcriptions`), or AiTunnel transcription (`/v1/audio/transcriptions`, RU profile only), and selects provider based on the chosen voice recognition model and configured keys.

## Project Structure
```text
.
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
│       │   │       ├── telemetry/          # Local telemetry outbox, batching sender, and event models
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

Notes:
- Directories like `.gradle/`, `.idea/`, `.kotlin/`, and `*/build/` are generated/local environment folders.
- macOS JNI/native binaries for packaged app resources are bundled from `composeApp/src/jvmMain/resources/common` (`darwin-arm64`, `darwin-x64`, plus top-level `libsqlitejdbc.dylib`).
- macOS signing config is now split by build mode: App Store builds (`-PmacOsAppStoreRelease=true`) use provisioning profiles + sandbox entitlements, while Developer ID DMG builds use non-App-Store entitlements and do not embed provisioning profiles.
- App Store sandbox entitlements include Calendar access + Downloads and user-selected file access, and runtime Info.plist adds calendar privacy usage descriptions; sandbox builds also degrade voice hotkey behavior gracefully when global input monitoring is unavailable (microphone-button voice input still works). `FilesToolUtil` also maps `~` to the real user home under sandbox (instead of container home), so `~/Downloads` resolves to the actual Downloads folder.
- macOS runtime image explicitly includes `java.net.http` so release app bundles contain `java.net.http.HttpClient` used by Telegram service startup.
- Voice recognition audio upload now sends raw PCM (`audio/x-pcm;bit=16;rate=16000`) directly to Salute Speech, so the app no longer depends on JAVE/embedded FFmpeg binaries for microphone transcription.
- OpenAI and AiTunnel voice transcription wrap recorded raw PCM (16kHz mono 16-bit) into a WAV container before multipart upload (`capture.wav`, `audio/wav`) because these transcription endpoints do not accept the recorder's raw PCM stream directly.
- `ConfigStore` now encrypts sensitive values (LLM API keys, Telegram bot token, MCP OAuth state, `MCP_SERVERS_JSON`) before writing to Java Preferences using AES-GCM + PBKDF2. `SOUZ_MASTER_KEY` (env var or JVM system property) can be used as an override; otherwise the app auto-generates and stores a local master key file in the user profile (platform-specific app config directory). Legacy plaintext values are read and transparently migrated to encrypted storage.
- Telemetry is always on and no longer exposed in Settings UI. `TelemetryService` starts from `Main.kt`, persists an auto-generated `userId`, `deviceId`, installation keypair, and server-issued `installationId` in `ConfigStore`, registers installations via `/v1/installations/register`, and sends signed batches to `/v1/metrics/batch`. Tool/request attribution is request-scoped to avoid cross-request mixing during cancellation/retry races, batching stays at 50 events, retries use exponential backoff, the local SQLite outbox remains `~/.local/state/souz/telemetry.db`, and error fields are sanitized down to safe identifiers instead of raw exception messages.
