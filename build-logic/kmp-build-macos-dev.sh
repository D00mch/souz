#!/bin/bash
set -euo pipefail

# =============================================================================
# Paths
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_GRADLE="$PROJECT_DIR/composeApp/build.gradle.kts"
MAIN_RELEASE_DIR="$PROJECT_DIR/composeApp/build/compose/binaries/main-release"
APP_OUTPUT_DIR="$MAIN_RELEASE_DIR/app"
DMG_OUTPUT_DIR="$MAIN_RELEASE_DIR/dmg"
DEST_DIR="$PROJECT_DIR/dest/homebrew"
COMPOSE_RUNTIME_CACHE_DIR="$PROJECT_DIR/composeApp/build/compose/tmp/main/runtime"
COMPOSE_CHECK_RUNTIME_DIR="$PROJECT_DIR/composeApp/build/compose/tmp/checkRuntime"

# =============================================================================
# Validation
# =============================================================================

usage() {
  cat <<'EOF'
Usage: kmp-build-macos-dev.sh --jdk-arch arm64|aarch64|x86_64|x64

Examples:
  ./build-logic/kmp-build-macos-dev.sh --jdk-arch arm64
  ./build-logic/kmp-build-macos-dev.sh --jdk-arch x86_64
EOF
}

extract_version() {
  sed -n 's/.*packageVersion = "\(.*\)".*/\1/p' "$BUILD_GRADLE" | head -n 1
}

JDK_ARCH_INPUT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jdk-arch)
      [[ $# -ge 2 ]] || { echo "Missing value for --jdk-arch" >&2; usage; exit 1; }
      JDK_ARCH_INPUT="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

case "$JDK_ARCH_INPUT" in
  arm64|aarch64)
    JDK_ARCH="arm64"
    ;;
  x86_64|x64)
    JDK_ARCH="x86_64"
    ;;
  *)
    echo "Unsupported --jdk-arch '$JDK_ARCH_INPUT'. Expected arm64|aarch64|x86_64|x64." >&2
    usage
    exit 1
    ;;
esac

if ! command -v /usr/libexec/java_home >/dev/null 2>&1; then
  echo "Missing /usr/libexec/java_home. This script must run on macOS." >&2
  exit 1
fi

if ! JDK_HOME="$(/usr/libexec/java_home -v 21 -a "$JDK_ARCH" 2>/dev/null)"; then
  echo "JDK 21 for architecture '$JDK_ARCH' was not found." >&2
  exit 1
fi

if [[ ! -x "$JDK_HOME/bin/java" ]]; then
  echo "Resolved JDK is invalid: $JDK_HOME" >&2
  exit 1
fi

VERSION="$(extract_version)"
if [[ -z "$VERSION" ]]; then
  echo "Unable to determine packageVersion from $BUILD_GRADLE." >&2
  exit 1
fi

case "$JDK_ARCH" in
  arm64)
    DMG_ARCH_SUFFIX="aarch64"
    ;;
  x86_64)
    DMG_ARCH_SUFFIX="X86_64"
    ;;
esac

DEST_VERSION_DIR="$DEST_DIR/$VERSION"
DEST_DMG_PATH="$DEST_VERSION_DIR/Souz_${DMG_ARCH_SUFFIX}-$VERSION.dmg"

if ! command -v security >/dev/null 2>&1; then
  echo "Missing 'security' tool. This script must run on macOS." >&2
  exit 1
fi

: "${APPLE_SIGNING_ID:?Missing APPLE_SIGNING_ID}"
: "${APPLE_ID:?Missing APPLE_ID}"
: "${APPLE_APP_SPECIFIC_PASSWORD:?Missing APPLE_APP_SPECIFIC_PASSWORD}"

if ! security find-identity -v | grep -Fq "$APPLE_SIGNING_ID"; then
  echo "Signing identity not found in keychain: $APPLE_SIGNING_ID" >&2
  exit 1
fi


# =============================================================================
# The real script
# =============================================================================

echo "JDK arch: $JDK_ARCH"
echo "JDK home: $JDK_HOME"

echo "Stopping Gradle daemons to avoid cross-architecture daemon reuse..."
"$PROJECT_DIR/gradlew" --stop >/dev/null 2>&1 || true

echo "Cleaning previous distributable output: $MAIN_RELEASE_DIR"
rm -rf "$MAIN_RELEASE_DIR"
echo "Cleaning cached Compose runtime image: $COMPOSE_RUNTIME_CACHE_DIR"
rm -rf "$COMPOSE_RUNTIME_CACHE_DIR" "$COMPOSE_CHECK_RUNTIME_DIR"

"$PROJECT_DIR/gradlew" :composeApp:notarizeReleaseDmg \
  -PmacOsAppStoreRelease=false \
  -Pmac.signing.enabled=true \
  -Pmac.signing.identity="$APPLE_SIGNING_ID" \
  -Pmac.notarization.enabled=true \
  -Pmac.notarization.appleId="$APPLE_ID" \
  -Pmac.notarization.password="$APPLE_APP_SPECIFIC_PASSWORD" \
  -Pmac.notarization.teamId=A6VYB9APPM \
  -Dorg.gradle.java.home="$JDK_HOME"

APP_BUNDLE_COUNT="$(find "$APP_OUTPUT_DIR" -maxdepth 1 -type d -name '*.app' | wc -l | tr -d ' ')"
if [[ "$APP_BUNDLE_COUNT" != "1" ]]; then
  echo "Expected exactly one .app bundle in $APP_OUTPUT_DIR, found $APP_BUNDLE_COUNT." >&2
  exit 1
fi

APP_BUNDLE_PATH="$(find "$APP_OUTPUT_DIR" -maxdepth 1 -type d -name '*.app' -print -quit)"
APP_NAME="$(basename "$APP_BUNDLE_PATH" .app)"
LAUNCHER_PATH="$APP_BUNDLE_PATH/Contents/MacOS/$APP_NAME"
RUNTIME_LIBJLI_PATH="$APP_BUNDLE_PATH/Contents/runtime/Contents/Home/lib/libjli.dylib"

if [[ ! -f "$LAUNCHER_PATH" ]]; then
  echo "Launcher binary is missing: $LAUNCHER_PATH" >&2
  exit 1
fi

if [[ ! -f "$RUNTIME_LIBJLI_PATH" ]]; then
  echo "Runtime libjli is missing: $RUNTIME_LIBJLI_PATH" >&2
  exit 1
fi

LAUNCHER_FILE_OUTPUT="$(file "$LAUNCHER_PATH")"
LIBJLI_FILE_OUTPUT="$(file "$RUNTIME_LIBJLI_PATH")"

if ! grep -Fq "$JDK_ARCH" <<<"$LAUNCHER_FILE_OUTPUT"; then
  echo "Launcher arch mismatch. Expected '$JDK_ARCH'." >&2
  echo "$LAUNCHER_FILE_OUTPUT" >&2
  exit 1
fi

if ! grep -Fq "$JDK_ARCH" <<<"$LIBJLI_FILE_OUTPUT"; then
  echo "Runtime libjli arch mismatch. Expected '$JDK_ARCH'." >&2
  echo "$LIBJLI_FILE_OUTPUT" >&2
  exit 1
fi

if [[ ! -d "$DMG_OUTPUT_DIR" ]]; then
  echo "Build finished, but DMG directory is missing: $DMG_OUTPUT_DIR" >&2
  exit 1
fi

DMG_PATH="$(find "$DMG_OUTPUT_DIR" -maxdepth 1 -type f -name '*.dmg' -print -quit)"
if [[ -z "$DMG_PATH" ]]; then
  echo "Build finished, but no DMG was found in: $DMG_OUTPUT_DIR" >&2
  exit 1
fi

mkdir -p "$DEST_VERSION_DIR"
mv -f "$DMG_PATH" "$DEST_DMG_PATH"
DMG_PATH="$DEST_DMG_PATH"

echo "Build verification passed."
echo "App bundle: $APP_BUNDLE_PATH"
echo "DMG: $DMG_PATH"
echo "$LAUNCHER_FILE_OUTPUT"
echo "$LIBJLI_FILE_OUTPUT"
