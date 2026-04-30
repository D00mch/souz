# Release

Release-specific notes moved from `AGENTS.md`.

## macOS Packaging and Signing

- macOS JNI/native binaries for packaged app resources are bundled from `composeApp/src/jvmMain/resources/common` (`darwin-arm64`, `darwin-x64`, plus top-level `libsqlitejdbc.dylib`); the llama bridge source binaries now live in `native/src/main/resources/darwin-*` and are mirrored into the app bundle during packaging.
- macOS signing config is split by build mode: App Store builds (`-PmacOsAppStoreRelease=true`) use provisioning profiles + sandbox entitlements, while Developer ID DMG builds use non-App-Store entitlements and do not embed provisioning profiles.
- App Store sandbox entitlements include Calendar access + Downloads and user-selected file access, and runtime Info.plist adds calendar privacy usage descriptions; sandbox builds also degrade voice hotkey behavior gracefully when global input monitoring is unavailable (microphone-button voice input still works). `FilesToolUtil` also maps `~` to the real user home under sandbox (instead of container home), so `~/Downloads` resolves to the actual Downloads folder.
- macOS runtime image explicitly includes `java.net.http` so release app bundles contain `java.net.http.HttpClient` used by Telegram service startup.
- Homebrew distribution is documented in `docs/homebrew.md`.
