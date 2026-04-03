#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_GRADLE="$PROJECT_DIR/composeApp/build.gradle.kts"
DEFAULT_GITHUB_REPO="D00mch/souz"
DEFAULT_TAP_CASKS_DIR="/Users/dumch/work/homebrew-tap/Casks"
TOKEN="souz-ai"
APP_NAME="Souz AI"
APP_BUNDLE="Souz AI.app"
DESC="Security-focused desktop AI assistant"
HOMEPAGE="https://souz.app"
MIN_MACOS=":monterey"
VERSION=""
RELEASE_TAG=""
GITHUB_REPO="$DEFAULT_GITHUB_REPO"
TAP_CASKS_DIR="$DEFAULT_TAP_CASKS_DIR"
OUTPUT=""

usage() {
  cat <<'EOF'
Usage:
  prepare-homebrew-release.sh [options]

Generate the Homebrew cask from DMGs already present in:
  dest/homebrew/<version>/

The script expects:
  Souz_aarch64-<version>.dmg
  Souz_X86_64-<version>.dmg

It writes:
  dest/homebrew/<version>/souz-ai.rb

And copies that file to:
  /Users/dumch/work/homebrew-tap/Casks/souz-ai.rb

Examples:
  ./build-logic/prepare-homebrew-release.sh
  ./build-logic/prepare-homebrew-release.sh --release-tag 1.0.4

Options:
  --version <version>          Override version. Defaults to composeApp/build.gradle.kts packageVersion.
  --release-tag <tag>          GitHub release tag. Defaults to version.
  --github-repo <owner/repo>   Release repository. Default: D00mch/souz
  --tap-casks-dir <path>       Destination tap Casks directory. Default: /Users/dumch/work/homebrew-tap/Casks
  --output <path>              Write generated cask to this path instead of dest/homebrew/<version>/souz-ai.rb
  -h, --help                   Show this help.
EOF
}

extract_version() {
  sed -n 's/.*packageVersion = "\(.*\)".*/\1/p' "$BUILD_GRADLE" | head -n 1
}

require_file() {
  local path="$1"
  local label="$2"
  if [[ ! -f "$path" ]]; then
    echo "$label does not exist: $path" >&2
    exit 1
  fi
}

compute_sha256() {
  local path="$1"
  shasum -a 256 "$path" | awk '{print $1}'
}

validate_sha256() {
  local value="$1"
  [[ "$value" =~ ^[0-9a-fA-F]{64}$ ]]
}

quote() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '"%s"' "$value"
}

generate_cask() {
  cat <<EOF
cask $(quote "$TOKEN") do
  version $(quote "$VERSION")

  on_arm do
    sha256 $(quote "$ARM64_SHA256")
    url $(quote "$RELEASE_BASE_URL/Souz_aarch64-$VERSION.dmg")
  end

  on_intel do
    sha256 $(quote "$INTEL_SHA256")
    url $(quote "$RELEASE_BASE_URL/Souz_X86_64-$VERSION.dmg")
  end

  name $(quote "$APP_NAME")
  desc $(quote "$DESC")
  homepage $(quote "$HOMEPAGE")

  livecheck do
    url :url
  end

  depends_on macos: ">= $MIN_MACOS"

  app $(quote "$APP_BUNDLE")

  zap trash: [
    $(quote "~/.local/state/souz"),
    $(quote "~/Library/Application Support/Souz"),
  ]
end
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      VERSION="${2:-}"
      shift 2
      ;;
    --release-tag)
      RELEASE_TAG="${2:-}"
      shift 2
      ;;
    --github-repo)
      GITHUB_REPO="${2:-}"
      shift 2
      ;;
    --tap-casks-dir)
      TAP_CASKS_DIR="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$VERSION" ]]; then
  VERSION="$(extract_version)"
fi

if [[ -z "$VERSION" ]]; then
  echo "Unable to determine packageVersion from $BUILD_GRADLE. Pass --version explicitly." >&2
  exit 1
fi

if [[ -z "$RELEASE_TAG" ]]; then
  RELEASE_TAG="$VERSION"
fi

if [[ -z "$OUTPUT" ]]; then
  OUTPUT="$PROJECT_DIR/dest/homebrew/$VERSION/souz-ai.rb"
fi

OUTPUT_DIR="$(dirname "$OUTPUT")"
DMG_INPUT_DIR="$PROJECT_DIR/dest/homebrew/$VERSION"
ARM64_DMG_PATH="$DMG_INPUT_DIR/Souz_aarch64-$VERSION.dmg"
INTEL_DMG_PATH="$DMG_INPUT_DIR/Souz_X86_64-$VERSION.dmg"
RELEASE_BASE_URL="https://github.com/$GITHUB_REPO/releases/download/$RELEASE_TAG"

require_file "$ARM64_DMG_PATH" "Apple Silicon DMG"
require_file "$INTEL_DMG_PATH" "Intel DMG"

ARM64_SHA256="$(compute_sha256 "$ARM64_DMG_PATH")"
INTEL_SHA256="$(compute_sha256 "$INTEL_DMG_PATH")"

for checksum in "$ARM64_SHA256" "$INTEL_SHA256"; do
  if ! validate_sha256 "$checksum"; then
    echo "Invalid SHA-256: $checksum" >&2
    exit 1
  fi
done

mkdir -p "$OUTPUT_DIR"
generate_cask > "$OUTPUT"

mkdir -p "$TAP_CASKS_DIR"
cp "$OUTPUT" "$TAP_CASKS_DIR/souz-ai.rb"

echo "Prepared Homebrew cask:"
echo "  arm64 dmg: $ARM64_DMG_PATH"
echo "  arm64 sha256: $ARM64_SHA256"
echo "  intel dmg: $INTEL_DMG_PATH"
echo "  intel sha256: $INTEL_SHA256"
echo "  cask: $OUTPUT"
echo "  tap cask: $TAP_CASKS_DIR/souz-ai.rb"
echo "  release tag: $RELEASE_TAG"
echo "  download base: $RELEASE_BASE_URL"
