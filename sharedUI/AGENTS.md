# Shared UI

The `:sharedUI` module contains the desktop Compose UI, view models, UI-facing adapters, host-port interfaces, a UI-only DI module, Compose resources, and UI/ViewModel JVM tests.

Only `:desktopApp` should depend on this module. Keep `:backend` independent from `:sharedUI`.

## Project Structure

```text
sharedUI/
├── build.gradle.kts
├── AGENTS.md
└── src/
    ├── commonMain/composeResources/        # Localized strings shared by generated Compose resources
    ├── jvmMain/
    │   ├── composeResources/drawable/      # Compose bundled drawables
    │   └── kotlin/ru/souz/
    │       ├── App.kt                      # Main Compose application surface
    │       ├── WindowLocals.kt             # Desktop window CompositionLocal APIs
    │       ├── di/                         # UI-related DI bindings imported by desktopApp
    │       ├── tool/telegram/              # UI approval adapters for Telegram selection prompts
    │       └── ui/                         # Compose screens, view models, theme, common components
    └── jvmTest/                            # Desktop behavior, integration, and UI/view-model tests
```

## Notes

- Keep UI composables presentation-only. Business logic and IO should stay in view models or delegated use cases.
- The main desktop composition root lives in `:desktopApp`; `SharedUiDiModule.kt` should bind only UI-facing use cases/adapters.
- Non-UI desktop services, tools, data extraction, and OS integrations live in `:desktopApp`.
- `src/jvmMain/kotlin/ru/souz/tool/telegram/TelegramSelectionApprovalSources.kt` adapts shared Telegram selection brokers into localized UI prompts.
- Before changing `ui/main`, `ui/settings`, or `tool/telegram`, read the nested `AGENTS.md` in that directory first.
