# ComposeApp

The `:composeApp` module contains the desktop application shell for Souz: Compose screens and view models, desktop-only services, macOS integrations, and the desktop tool catalog layered on top of `:runtime`, `:agent`, `:llms`, and `:native`.

This module owns:

- desktop DI startup and application wiring;
- OS-bound services such as audio, hotkeys, permissions, image helpers, and MCP sessions;
- desktop-only tools for browser, calendar, mail, notes, presentations, desktop automation, and app launch flows;
- setup, chat, settings, graph log, and tool-management UI flows;
- JVM tests for desktop behavior, integrations, and UI/view-model logic.

## Project Structure

```text
composeApp/
├── build.gradle.kts                               # Compose Desktop/KMP module build
├── proguard-rules.pro                             # Release shrinker rules
├── AGENTS.md                                      # This file
└── src/
    ├── commonMain/
    │   └── composeResources/
    │       └── values{,-ru}/strings.xml           # Shared localized strings
    ├── jvmMain/
    │   ├── composeResources/
    │   │   └── drawable/                          # Compose bundled drawables
    │   ├── kotlin/
    │   │   └── ru/souz/
    │   │       ├── db/                            # Desktop data extraction, vector DB, and info repositories
    │   │       ├── di/                            # Desktop dependency graph and startup wiring
    │   │       ├── llms/                          # Desktop voice provider adapters and provider-specific notes
    │   │       ├── service/
    │   │       │   ├── audio/                     # Audio capture and playback helpers
    │   │       │   ├── image/                     # Image utility helpers
    │   │       │   ├── keys/                      # Global hotkey and native key abstractions
    │   │       │   ├── mcp/                       # MCP config, OAuth, session, and client management
    │   │       │   ├── observability/             # Structured local telemetry/observability logs
    │   │       │   ├── permissions/               # macOS permission checks and app relaunch helpers
    │   │       │   ├── telegram/
    │   │       │   │   └── AGENTS.md
    │   │       ├── tool/
    │   │       │   ├── ToolsFactory.kt            # Desktop tool registration and assembly
    │   │       │   ├── ToolsSettings.kt           # Desktop tool settings/state models
    │   │       │   ├── SelectionApprovalSource.kt # Selection approval sources shared by desktop tools
    │   │       │   ├── ToolRunBashCommand.kt      # Local shell execution tool wrapper
    │   │       │   ├── application/               # Installed-app discovery and open tools
    │   │       │   ├── browser/                   # Browser navigation, focus, and hotkey tools
    │   │       │   ├── calendar/                  # Calendar tools and AppleScript commands
    │   │       │   ├── config/                    # Desktop instruction/config helpers
    │   │       │   ├── desktop/                   # Screenshot, input, window, and desktop automation tools
    │   │       │   ├── files/                     # Desktop file-selection bridge tools
    │   │       │   ├── mail/                      # Mail.app tools and AppleScript commands
    │   │       │   ├── notes/                     # Notes app tools
    │   │       │   ├── presentation/              # Presentation theme and create/read tools
    │   │       │   ├── telegram/
    │   │       │   │   └── AGENTS.md
    │   │       │   └── textReplace/               # Clipboard, selection, and text replacement tools
    │   │       └── ui/
    │   │           ├── AppTheme.kt                # Shared desktop theme setup
    │   │           ├── BaseViewModel.kt           # Shared ViewModel base helpers
    │   │           ├── DockWindowController.kt    # Dock/tray style window coordination
    │   │           ├── common/                    # Shared dialogs, markdown, download, and window helpers
    │   │           ├── components/                # Reusable composables
    │   │           ├── graphlog/                  # Graph session and visualization screens
    │   │           ├── macos/                     # macOS window-effect helpers
    │   │           ├── main/
    │   │           │   └── AGENTS.md
    │   │           ├── settings/
    │   │           │   └── AGENTS.md
    │   │           ├── setup/                     # First-run setup flow
    │   │           └── tools/                     # Tool details and tool-settings screens/view models
    │   ├── proto/
    │   │   └── gigachat/v1/gigachatv1.proto       # Giga voice protobuf schema
    │   └── resources/
    │       ├── certs/                             # Bundled trust certificates
    │       ├── common/                            # Shared native libraries/resources
    │       ├── darwin-arm64/                      # macOS arm64 native libraries
    │       ├── darwin-x64/                        # macOS x64 native libraries
    │       ├── scripts/                           # Bundled helper/build scripts
    │       └── support/                           # Support and policy HTML assets
    └── jvmTest/
        ├── kotlin/
        │   ├── agent/                             # Desktop agent scenario and integration coverage
        │   ├── db/                                # Desktop DB/repository tests
        │   ├── giga/                              # Giga-specific tests
        │   ├── ru/souz/                           # Package-aligned desktop unit and integration tests
        │   └── tool/                              # Tool tests plus file-tool coverage
        └── resources/                             # Test fixtures and sample files
```

## Notes

- Keep UI composables presentation-only. Business logic and IO should stay in view models or delegated use cases.
- `src/jvmMain/kotlin/ru/souz/di/Dependencies.kt` is the main desktop composition root.
- Desktop sandbox wiring now goes through `RuntimeSandboxFactory`; local mode remains the default, and Docker mode is opt-in through `SOUZ_SANDBOX_MODE=docker`.
- `src/jvmMain/kotlin/ru/souz/tool/ToolsFactory.kt` is the main entry point for adding or removing desktop tools.
- Before changing `ui/main`, `ui/settings`, `service/telegram`, `service/observability`, or `tool/telegram`, read the nested `AGENTS.md` in that directory first.
