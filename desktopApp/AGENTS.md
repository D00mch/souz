# Desktop App

The `:desktopApp` module contains the runnable desktop entry points, desktop DI composition root, OS integrations, desktop-only tools/services, and Compose Desktop packaging/distribution configuration for Souz.

It depends on `:sharedLogic` and `:sharedUI`. Keep Compose screens, view models, UI adapters, and UI tests in `:sharedUI`; keep backend-safe runtime logic in `:sharedLogic`; keep OS-bound desktop services/tools and app composition wiring here.

Desktop-only persistence such as the SQLite working-memory repository belongs here when the shared layer only needs contracts/services.

## Project Structure

```text
desktopApp/
├── build.gradle.kts                    # Compose Desktop application/package tasks
├── proguard-rules.pro                  # Release shrinker rules
├── AGENTS.md
└── src/
    └── main/
        ├── kotlin/ru/souz/
        │   ├── db/                      # Desktop indexing/data extraction
        │   ├── di/                      # Desktop composition root
        │   ├── memory/                  # Desktop SQLite memory repository and runtime bridge
        │   ├── service/                 # Audio, image capture, keys, permissions, TDLight Telegram
        │   ├── tool/                    # Browser/calendar/mail/notes/desktop/application/text/Telegram tools
        │   ├── Main.kt                 # Windowed Compose Desktop entry point
        │   └── TextMain.kt             # Text-mode agent entry point
        ├── proto/                      # Giga voice protobuf schema
        └── resources/
            ├── certs/                  # Bundled trust certificates
            ├── common/                 # Shared native libraries/resources
            ├── darwin-arm64/           # macOS arm64 native libraries
            ├── darwin-x64/             # macOS x64 native libraries
            ├── scripts/                # Bundled helper/build scripts
            └── support/                # Support and policy HTML assets
```

## Notes

- Run the desktop app with `./gradlew :desktopApp:run`.
- Run desktop host/tool/service tests with `./gradlew :desktopApp:test`.
- Release/distribution tasks such as `createReleaseDistributable`, `packageReleaseDmg`, and `notarizeReleaseDmg` live under `:desktopApp`.
- Desktop sandbox wiring is provided by `:sharedUI`/`:sharedLogic`; Docker mode is opt-in through `SOUZ_SANDBOX_MODE=docker`.
