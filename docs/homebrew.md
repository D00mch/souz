# Homebrew

- [Acceptable Formulae](https://docs.brew.sh/Acceptable-Formulae)
- [Cask Cookbook](https://docs.brew.sh/Cask-Cookbook)
- [Taps](https://docs.brew.sh/Taps)

## Installation

```bash
brew tap D00mch/tap
brew install --cask souz-ai
```

## Current repo status

Relevant current packaging details:

- App bundle name: `Souz AI.app`
- Bundle id: `ru.souz`
- Minimum macOS version in Gradle packaging: `12.0` (`monterey`)
- Release packaging already targets macOS DMG/PKG in [desktopApp/build.gradle.kts](../desktopApp/build.gradle.kts)
- Developer ID notarized DMG flow exists in [scripts/kmp-build-macos-dev.sh](../scripts/kmp-build-macos-dev.sh)
- `scripts/kmp-build-macos-dev.sh` renames the final DMG to `Souz_aarch64-<version>.dmg` for Apple Silicon and `Souz_X86_64-<version>.dmg` for Intel.

## Generate the cask

This repo uses a single script, [prepare-homebrew-release.sh](../scripts/prepare-homebrew-release.sh), to calculate SHA-256 values from the exported DMGs in `dest/homebrew/<version>/`, generate `souz-ai.rb`, and copy it into the tap repo.

### Build Then Generate

Build both architectures first:

```bash
./scripts/kmp-build-macos-dev.sh --jdk-arch arm64
./scripts/kmp-build-macos-dev.sh --jdk-arch x86_64
```

The build script exports each finished DMG to `dest/homebrew/<version>/`, so after the second run you should have both:

- `Souz_aarch64-<version>.dmg`
- `Souz_X86_64-<version>.dmg`

Then generate and copy the cask:

```bash
./scripts/prepare-homebrew-release.sh --release-tag 1.0.5
```

This build flow requires the usual signing and notarization environment variables for [kmp-build-macos-dev.sh](../scripts/kmp-build-macos-dev.sh): `APPLE_SIGNING_ID`, `APPLE_ID`, and `APPLE_APP_SPECIFIC_PASSWORD`.

The Homebrew script reads these DMGs directly from:

```text
dest/homebrew/<version>/
```

By default, it writes:

- the generated cask to `dest/homebrew/<version>/souz-ai.rb`
- a copy to `/Users/dumch/work/homebrew-tap/Casks/souz-ai.rb`

### Separate arm64 and x86_64 DMGs

```bash
./scripts/prepare-homebrew-release.sh \
  --version 1.0.4 \
  --release-tag 1.0.4
```

By default, the script reads the app version from [desktopApp/build.gradle.kts](../desktopApp/build.gradle.kts). Pass `--version` if the release tag and Gradle version are temporarily out of sync.

Default GitHub release asset names:

- `Souz_aarch64-<version>.dmg`
- `Souz_X86_64-<version>.dmg`
