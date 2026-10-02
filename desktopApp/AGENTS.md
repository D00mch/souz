# Desktop App

`:desktopApp` is the runnable Compose Desktop host. It owns process lifecycle and DI composition, OS integrations, desktop-only tools and services, desktop persistence adapters, and packaging resources.

Before changing this module, read the [pain-point index](docs/pain-points.md) and the topics relevant to the area.

## Boundaries

- Keep screens, ViewModels, UI host-port contracts, and UI tests in `:sharedUI`; keep portable runtime logic in `:sharedLogic`.
- Keep the desktop composition root and lifecycle ownership here. Platform services may implement shared contracts, but shared modules must not depend on desktop implementations.
- OS-bound browser, mail, calendar, automation, audio, native-key, indexing, and Telegram integrations belong here.
- Desktop-only persistence belongs here when shared layers depend only on its contracts.
- Preserve the build's native-resource preparation, signing, and distribution boundaries when changing packaged resources.

## Verification

- Run the app: `./gradlew :desktopApp:run`
- Run host/tool/service tests: `./gradlew :desktopApp:test`
- Hindsight conversation evaluations are opt-in and default to a scripted agent LLM; see [the scenario guide](docs/hindsight-scenarios.md). Keep probe capture disabled, wait for seed capture, and mark scripted answer-quality checks as skipped.
- Build a release distribution: `./gradlew :desktopApp:createReleaseDistributable`
