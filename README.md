# souz 

A desktop Agent app to help with routines.

# Documentation

- [Agent engine](composeApp/src/jvmMain/kotlin/ru/souz/agent/engine/README.md)
- [MCP Integration](composeApp/src/jvmMain/kotlin/ru/souz/mcp/README.md)

# Developers notes
 
For Intellij IDEA you need next plugins:
- Kotlin Multiplatform;
- Compose Multiplatform;
- Compose Multiplatform for Desktop IDE support;

To launch preview rendering, press the desktop preview button near the composable.
       
Run tests with:
```bash
./gradlew :composeApp:cleanJvmTest :composeApp:jvmTest
```

# Compose builds

## Build Editions
- Edition is selected by Gradle property `-Pedition=ru|en` (default: `ru`) in `composeApp/build.gradle.kts`.
- Dedicated DMG tasks infer edition automatically by task name when `-Pedition` is not provided.
- Runtime edition is passed via `-Dsouz.edition` (or `souz_EDITION` fallback) and parsed in `composeApp/src/jvmMain/kotlin/ru/souz/edition/BuildEdition.kt`.
- Packaging metadata depends on edition:
  - `ru`: package name `Союз ИИ`, bundle ID `ru.souz`, Dock name `Союз c ИИ`.
  - `en`: package name `Souz AI`, bundle ID `en.souz`, Dock name `Souz AI`.
- Dedicated DMG tasks:
  - `./gradlew :composeApp:packageRuReleaseDmg`
  - `./gradlew :composeApp:packageEnReleaseDmg`
- Edition-specific runtime profile (`composeApp/src/jvmMain/kotlin/ru/souz/giga/LlmBuildProfile.kt`):
  - `ru`: `GIGA`, `QWEN`, `AI_TUNNEL`; SaluteSpeech recognition is enabled.
  - `en`: `QWEN`, `ANTHROPIC`; speech recognition is disabled (`DisabledSpeechRecognitionProvider`).
- Edition-specific key fields/providers are configured in `composeApp/src/jvmMain/kotlin/ru/souz/ui/common/ApiKeyProviders.kt` and drive Setup/Settings key UI.

## Release builds

- Take a look at the [KMP release documentation](https://github.com/JetBrains/compose-multiplatform/blob/master/tutorials/Signing_and_notarization_on_macOS/README.md).
- Use [kmp-build-macos-universal.sh](build-logic/kmp-build-macos-universal.sh) script to prepare app bundles.
- Use [kmp-build-macos-dev.sh](build-logic/kmp-build-macos-dev.sh) script for publishing outside the App Store.