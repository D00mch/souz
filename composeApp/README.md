# Compose builds

## Build Editions
- Edition is selected by Gradle property `-Pedition=ru|en` (default: `ru`) in `composeApp/locality.gradle.kts`.
- Runtime edition is passed via `-Dgigadesk.edition` (or `GIGADESK_EDITION` fallback) and parsed in `composeApp/src/jvmMain/kotlin/ru/gigadesk/edition/BuildEdition.kt`.
- Packaging metadata depends on edition:
    - `ru`: package name `Союз ИИ`, bundle ID `ru.gigadesk`, Dock name `Союз c ИИ`.
    - `en`: package name `Souz AI`, bundle ID `en.gigadesk`, Dock name `Souz AI`.
- Dedicated DMG tasks:
    - `./gradlew -Pedition=ru :composeApp:packageRuReleaseDmg`
    - `./gradlew -Pedition=en :composeApp:packageEnReleaseDmg`
- Edition-specific runtime profile (`composeApp/src/jvmMain/kotlin/ru/gigadesk/giga/LlmBuildProfile.kt`):
    - `ru`: `GIGA`, `QWEN`, `AI_TUNNEL`; SaluteSpeech recognition is enabled.
    - `en`: `QWEN`, `ANTHROPIC`; speech recognition is disabled (`DisabledSpeechRecognitionProvider`).
- Edition-specific key fields/providers are configured in `composeApp/src/jvmMain/kotlin/ru/gigadesk/ui/common/ApiKeyProviders.kt` and drive Setup/Settings key UI.
