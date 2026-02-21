#!/bin/bash
set -euo pipefail

# =============================================================================
# Validation
# =============================================================================

usage() {
  cat <<'EOF'
Usage: kmp-build-macos-dev.sh --edition ru|en --jdk-arch arm64|aarch64|x86_64|x64

Examples:
  ./build-logic/kmp-build-macos-dev.sh --edition ru --jdk-arch arm64
  ./build-logic/kmp-build-macos-dev.sh --edition en --jdk-arch x86_64
EOF
}

BUILD_EDITION=""
JDK_ARCH_INPUT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --edition)
      [[ $# -ge 2 ]] || { echo "Missing value for --edition" >&2; usage; exit 1; }
      BUILD_EDITION="$2"
      shift 2
      ;;
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

case "$BUILD_EDITION" in
  ru|en) ;;
  *)
    echo "Unsupported edition '$BUILD_EDITION'. Expected ru or en." >&2
    usage
    exit 1
    ;;
esac

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

if ! command -v security >/dev/null 2>&1; then
  echo "Missing 'security' tool. This script must run on macOS." >&2
  exit 1
fi

if ! security find-identity -v | grep -Fq "$APPLE_SIGNING_ID"; then
  echo "Signing identity not found in keychain: $APPLE_SIGNING_ID" >&2
  exit 1
fi


# =============================================================================
# The real script
# =============================================================================

: "${APPLE_SIGNING_ID:?Missing APPLE_SIGNING_ID}"
: "${APPLE_ID:?Missing APPLE_ID}"
: "${APPLE_APP_SPECIFIC_PASSWORD:?Missing APPLE_APP_SPECIFIC_PASSWORD}"

echo "Edition: $BUILD_EDITION"
echo "JDK arch: $JDK_ARCH"
echo "JDK home: $JDK_HOME"

./gradlew :composeApp:notarizeReleaseDmg \
  -Pedition="$BUILD_EDITION" \
  -PmacOsAppStoreRelease=false \
  -Pmac.signing.enabled=true \
  -Pmac.signing.identity="$APPLE_SIGNING_ID" \
  -Pmac.notarization.enabled=true \
  -Pmac.notarization.appleId="$APPLE_ID" \
  -Pmac.notarization.password="$APPLE_APP_SPECIFIC_PASSWORD" \
  -Pmac.notarization.teamId=A6VYB9APPM \
  -Dorg.gradle.java.home="$JDK_HOME"
