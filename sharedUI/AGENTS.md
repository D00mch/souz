# Shared UI

The `:sharedUI` module contains reusable Android/Desktop Compose presentation in `commonMain`, desktop Compose UI, view models, UI-facing adapters, host-port interfaces, a UI-only DI module, Compose resources, and UI/ViewModel JVM tests.

Only UI hosts such as `:desktopApp` and `:androidApp` should depend on this module. Keep `:backend` independent from `:sharedUI`.

## Project Structure

```text
sharedUI/
├── build.gradle.kts
├── AGENTS.md
└── src/
    ├── commonMain/
    │   ├── composeResources/              # Localized strings shared by generated Compose resources
    │   └── kotlin/ru/souz/ui/             # Platform-neutral presentation DTOs, callbacks, chat/settings surfaces, and common primitives
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

- Keep `commonMain` composables presentation-only. They expose state DTOs and callback/events only; business logic, persistence, DI, IO, permissions, tool execution, file pickers, local models, memory, MCP, and desktop window behavior must stay in platform adapters, view models, or delegated use cases.
- The main desktop composition root lives in `:desktopApp`; `SharedUiDiModule.kt` should bind only UI-facing use cases/adapters.
- Non-UI desktop services, tools, data extraction, and OS integrations live in `:desktopApp`.
- `src/jvmMain/kotlin/ru/souz/tool/telegram/TelegramSelectionApprovalSources.kt` adapts shared Telegram selection brokers into localized UI prompts.
- Before changing `ui/main`, `ui/settings`, or `tool/telegram`, read the nested `AGENTS.md` in that directory first.
