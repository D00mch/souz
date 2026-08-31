# Android App

`:androidApp` is the Android entry point for Souz. It hosts `MainActivity`, builds the Android dependency graph in `AndroidAgentRuntime`, renders the Android Compose surface from `:sharedUI`, and runs the selected graph agent through the Android-safe runtime pieces in `:sharedLogic`. `SkillsGraphBasedAgent` is the default.

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

Default Android builds target the `armeabi-v7a` ABI only and omit the embedded Python runtime, which together account for roughly 40 MB of APK size. Devices that report both `armeabi-v7a` and legacy `armeabi` are covered by the `armeabi-v7a` build.

Build for other ABIs with a comma separated list:

```bash
./gradlew :androidApp:assembleDebug -Psouz.android.abis=armeabi-v7a,arm64-v8a,x86_64
```

Skills that execute Python need the embedded Chaquopy runtime, which is opt-in. Without it, `PYTHON` skill commands fail and the rest of the app is unaffected:

```bash
./gradlew :androidApp:assembleDebug -Psouz.android.python=true
```

Chaquopy builds include Python 3.11 standard library support and app Python sources only, and do not require host Python. To also bundle document/data skill packages (`lxml`, `Pillow`, `XlsxWriter`, and `python-pptx`), add:

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

## Becoming The System Assistant

The remote's assist key is routed by `PhoneWindowManager`, which reads `Settings.Secure.assistant`.
Installing the app is not enough; it has to hold the assistant role:

```bash
adb shell cmd role remove-role-holder --user 0 android.app.role.ASSISTANT ru.souz.android
adb shell cmd role add-role-holder    --user 0 android.app.role.ASSISTANT ru.souz.android
adb shell pm grant ru.souz.android android.permission.RECORD_AUDIO
```

The removal is not redundant. After a reinstall the role record can survive while the secure
settings are cleared, and `add-role-holder` then answers "Package is already a role holder" and
writes nothing. The service ends up bound while `Settings.Secure.assistant` stays empty, so the
assist key is silently dropped.

Both settings should name the voice interaction service afterwards:

```bash
adb shell settings get secure assistant
adb shell settings get secure voice_interaction_service
adb shell dumpsys voiceinteraction | head -20
```

## RuStore Catalogue Search

`RuStoreSearch` looks up apps that are not installed. RuStore exposes no search intent and no search
deep link, only its Android TV suggestion provider at authority `rustore.search`, which requires
`android.permission.GLOBAL_SEARCH`.

That permission is `signature|privileged`, so `adb shell pm grant` cannot hand it out. The build has
to be platform signed, or installed under `priv-app` and whitelisted:

```xml
<privapp-permissions package="ru.souz.android">
    <permission name="android.permission.GLOBAL_SEARCH"/>
</privapp-permissions>
```

Without it the tool returns a SecurityException message and the rest of the app is unaffected.
Results carry a store deep link; open one with the `Open` tool, which already accepts arbitrary
URIs such as `market://details?id=<package>`.

## Provisioning Credentials Over adb

Typing API keys with a TV remote is painful, so keys can be provisioned by broadcast:

```bash
adb shell am broadcast -W -f 0x00000020 \
  -n ru.souz.android/.provisioning.SouzProvisioningReceiver \
  -a ru.souz.android.action.PROVISION \
  --es aitunnel 'sk-aitunnel-...' \
  --es gigachat '...'
```

`-f 0x00000020` is `FLAG_INCLUDE_STOPPED_PACKAGES`. An app that has not been launched since it was installed is in the stopped state, and broadcasts are dropped without that flag, silently: exactly the case when provisioning a freshly flashed device.

Supported extras: `gigachat`, `aitunnel`, `openai`, `openai_base_url`, `openai_model`, and `salutespeech`. `-W` makes `am` print which settings were applied; values are never logged.

An empty value clears that setting, but the whole command has to be quoted so the empty argument survives: `adb shell` reassembles its arguments and the device shell parses them again, so a bare `--es aitunnel ''` arrives as `--es aitunnel --es`, silently storing the literal `--es`.

```bash
adb shell "am broadcast -W \
  -n ru.souz.android/.provisioning.SouzProvisioningReceiver \
  -a ru.souz.android.action.PROVISION \
  --es aitunnel '' --es gigachat ''"
```

The receiver requires senders to hold `android.permission.WRITE_SECURE_SETTINGS`, which shell and system hold and installed apps cannot obtain. Without that gate any app could point the assistant at its own OpenAI-compatible proxy and capture conversations.

Keys are stored the same way the Settings screen stores them, in Keystore-encrypted `SharedPreferences`. A running app picks them up on the next request; the Settings screen shows them after it is reopened.

Run Android instrumentation tests on a connected device or emulator:

```bash
./gradlew :androidApp:connectedDebugAndroidTest
```

## Supported

- Chat UI backed by shared `MainViewModel` and the selected graph agent.
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
  - Python commands run through the embedded Chaquopy Python 3.11 service process, when built with `souz.android.python=true`
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
- `androidApp/src/pythonRuntime/kotlin/ru/souz/android/python/` - Chaquopy skill execution bridge and service protocol, compiled only when the Python runtime is enabled.
- `androidApp/src/noPython/kotlin/ru/souz/android/python/` - stand-in used when it is not.
- `androidApp/src/main/python/souz_skill_runner.py` - Python-side skill runner entry point.
- `sharedUI/src/androidMain/kotlin/ru/souz/ui/android/` - Android Compose screens backed by shared ViewModels.
- `sharedLogic/src/androidMain/kotlin/ru/souz/android/` - Android settings and runtime sandbox implementations.

## Development Notes

- UI code should stay presentation-only. Put UI coordination in ViewModels and runtime behavior in `:sharedLogic` or Android host adapters.
- Do not assume desktop filesystem paths. Android tools and skills should work inside the app-private sandbox roots.
- Do not write Android skills that require GNU Bash. The Android shell runtime is `/system/bin/sh`.
- Prefer pure-Kotlin/shared implementations when behavior should also work on desktop or backend.
