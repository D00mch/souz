# Android App

`:androidApp` is the Android application host. It owns the activity, application composition, Android runtime wiring, and app-local storage adapters; reusable UI and runtime behavior belong to shared modules.

Before changing this module, read its [pain-point index](docs/pain-points.md).

## Boundaries

- Keep Android screens and routes in `:sharedUI` `androidMain`; keep shared ViewModels, state, events, and host-port contracts in `commonJvmMain`.
- Build one Android Kodein graph for settings, storage, tools, and `AgentFacade` so host singletons share one lifecycle.
- Reuse Android-safe settings, sandbox, skill, and provider implementations from `:sharedLogic`; never introduce AWT/Swing, desktop Compose windows, native desktop models, or desktop services.
- Bind Android implementations or explicit no-ops for shared host ports that have no mobile capability.
- Keep skill command execution compatible with Android's POSIX `/system/bin/sh`; do not assume GNU Bash.

## Verification

Build the debug application with `./gradlew :androidApp:assembleDebug`.
