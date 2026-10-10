# Shared UI

`:sharedUI` owns the desktop UI layer: portable Compose resources and primitives, shared ViewModels and UI logic, host-port contracts, and platform-specific screens and adapters. UI hosts may depend on this module; `:backend` must not.

Before changing this module, read the relevant pain-point topics below.

## Boundaries

- Composables render state and emit events. Keep business logic, persistence, permissions, tool execution, and other IO in ViewModels, use cases, or host adapters.
- Keep `commonJvmMain` free of AWT/Swing, desktop window APIs, native-model classes, and OS-bound side effects. Express platform work through host ports with no-op implementations where appropriate.
- Put desktop windows, pickers, drag/drop, native integrations, and desktop-only screens in `jvmMain`.
- The desktop composition root belongs to `:desktopApp`. Shared UI DI may bind only UI-facing use cases and adapters.

## Pain points

- [Chat search](docs/chat-search.md) — rendered-text offsets, projection reuse, and highlighting consistency.
- [Chat execution](docs/chat-execution.md) — active-run input, provisional streaming state, and cancellation ownership.
- [Conversation cleanup](docs/conversation-cleanup.md) — conversation metadata capture and temporary state lifetime.
- [Desktop locale](docs/desktop-locale.md) — interface language, formatting locale, and the agent's startup locale.

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

- Build the module: `./gradlew :sharedUI:build`
- Run JVM UI/ViewModel tests: `./gradlew :sharedUI:jvmTest`
