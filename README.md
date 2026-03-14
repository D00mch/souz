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

## Test build to simulate App Store release build

```bash
# Package sandbox DMG
./gradlew :composeApp:packageReleaseDmg -PmacOsAppStoreRelease=true -Pmac.signing.enabled=true -Pmac.signing.identity="$APPLE_SIGNING_ID"

# Verify entitlements on built app
codesign -d --entitlements :- "composeApp/build/compose/binaries/main-release/app/Souz AI.app"

# reset permission
tccutil reset All ru.souz

# open it
open -a "$(pwd)/composeApp/build/compose/binaries/main-release/app/Souz AI.app"
```

## Runtime profile (EN/RU)
- Build is now single-profile at packaging time (no `-Pedition` split).
- Active EN/RU profile is selected at runtime from Settings (`General` section toggle) and persisted in `ConfigStore.APP_LANGUAGE`.
- Runtime profile controls model/provider availability in:
  - `composeApp/src/jvmMain/kotlin/ru/souz/giga/LlmBuildProfile.kt`
  - `composeApp/src/jvmMain/kotlin/ru/souz/ui/common/ApiKeyProviders.kt`
  - `composeApp/src/jvmMain/kotlin/ru/souz/ui/settings/ModelAvailability.kt`
- Default DMG task:
  - `./gradlew :composeApp:packageReleaseDmg`

## Release builds

- Take a look at the [KMP release documentation](https://github.com/JetBrains/compose-multiplatform/blob/master/tutorials/Signing_and_notarization_on_macOS/README.md).
- Use [kmp-build-macos-universal.sh](build-logic/kmp-build-macos-universal.sh) script to prepare app bundles.
- Use [kmp-build-macos-dev.sh](build-logic/kmp-build-macos-dev.sh) script for publishing outside the App Store.
