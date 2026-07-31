# Android App

`:androidApp` is the Android entry point for Souz. It hosts `MainActivity`, builds the Android dependency graph in `AndroidAgentRuntime`, renders the Android Compose surface from `:sharedUI`, and runs `GraphBasedAgent` through the Android-safe runtime pieces in `:sharedLogic`.

The Android app is a chat-agent host, not a full port of the desktop app. Keep Android-specific platform code here, reusable UI logic in `:sharedUI`, and reusable runtime/tool logic in `:sharedLogic`.

## Requirements

- Android Studio or an Android SDK installation with `adb`.
- An emulator or device running Android API 26 or newer.
- The repo Gradle wrapper from the project root.
- Network access for Gradle dependency resolution and remote model providers.
- At least one configured provider key, or a connected Codex account, to send chat requests.

The root build uses a Java 21 Gradle toolchain. Use the wrapper commands below from the repository root.

## Run

Build a debug APK:

```bash
./gradlew :androidApp:assembleDebug
```

Default Android builds include Chaquopy Python 3.11 standard library support and app Python sources only, and do not require host Python. The Android APK targets `armeabi-v7a`, `arm64-v8a`, and `x86_64` native runtimes; devices that report both `armeabi-v7a` and legacy `armeabi` are covered by the `armeabi-v7a` build. To also bundle document/data skill packages (`lxml`, `Pillow`, `XlsxWriter`, and `python-pptx`), build with:

```bash
./gradlew :androidApp:assembleDebug -Psouz.android.bundlePythonRequirements=true
```

When bundled packages are enabled, the build needs Python 3.11. You can point Chaquopy at an interpreter with `-Psouz.android.buildPython=/path/to/python3.11` or `SOUZ_ANDROID_BUILD_PYTHON`.

Install it on the connected device or emulator:

```bash
./gradlew :androidApp:installDebug
```

Launch the installed app:

```bash
adb shell am start -n ru.souz.android/.MainActivity
```

You can also open the repo in Android Studio and run the `androidApp` configuration.

Run Android instrumentation tests on a connected device or emulator:

```bash
./gradlew :androidApp:connectedDebugAndroidTest
```

## Supported

- Chat UI backed by shared `MainViewModel` and `GraphBasedAgent`.
- Settings UI for model selection, provider credentials, safe mode, language profile, request timeout, context size, temperature, and provider links.
- Remote chat providers wired through the Android runtime: GigaChat, Qwen, AiTunnel, Anthropic, OpenAI, and Codex.
- Provider secrets stored with Android Keystore-backed encryption in app `SharedPreferences`.
- Codex device-code account connection from the Settings screen.
- Portable runtime tools scoped to Android app-private storage:
  - file listing, reading, search, create, modify, move, and delete
  - image understanding through configured vision-capable providers
  - OpenAI image generation when OpenAI access is configured
  - web search, research, and web page text extraction
  - calculator
- ClawHub/OpenClaw-style skills stored in the app-private filesystem registry.
- Skill command execution through the Android sandbox:
  - shell commands run with POSIX `/system/bin/sh`
  - Python commands run through the embedded Chaquopy Python 3.11 service process
- Runtime state under app-private files, including `souz-home`, `souz-workspace`, and `souz-state`.

Android skills can use the Python standard library plus pure-Python files vendored inside a skill bundle. Runtime `pip install` is intentionally unsupported. The optional bundled document/data packages (`lxml`, `Pillow`, `XlsxWriter`, and `python-pptx`) are available only when `souz.android.bundlePythonRequirements=true`.

## Desktop-Only Or Not Yet Wired On Android

- Local llama.cpp model execution and native local-model downloads.
- Docker sandbox mode and `SOUZ_SANDBOX_MODE`.
- MCP tool providers.
- Node.js skill runtime.
- GNU Bash-specific skill scripts.
- Desktop automation tools, including browser control, app launch, global hotkeys, screenshots, screen recording, calendar, mail, notes, Telegram, and desktop text replacement.
- Desktop memory management UI.

## Permissions

The manifest declares network access plus optional permissions for microphone, camera, notifications, and media library access. Runtime permission prompts are handled by `MainActivity` when a feature needs them.

## Important Paths

- `androidApp/src/main/kotlin/ru/souz/android/MainActivity.kt` - Android entry point and runtime permission launcher.
- `androidApp/src/main/kotlin/ru/souz/android/agent/AndroidAgentRuntime.kt` - Android DI graph, provider clients, runtime sandbox, tools, and agent facade wiring.
- `androidApp/src/main/kotlin/ru/souz/android/python/` - Chaquopy skill execution bridge and service protocol.
- `androidApp/src/main/python/souz_skill_runner.py` - Python-side skill runner entry point.
- `sharedUI/src/androidMain/kotlin/ru/souz/ui/android/` - Android Compose screens backed by shared ViewModels.
- `sharedLogic/src/androidMain/kotlin/ru/souz/android/` - Android settings and runtime sandbox implementations.

## Development Notes

- UI code should stay presentation-only. Put UI coordination in ViewModels and runtime behavior in `:sharedLogic` or Android host adapters.
- Do not assume desktop filesystem paths. Android tools and skills should work inside the app-private sandbox roots.
- Do not write Android skills that require GNU Bash. The Android shell runtime is `/system/bin/sh`.
- Prefer pure-Kotlin/shared implementations when behavior should also work on desktop or backend.
