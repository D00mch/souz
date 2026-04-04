#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
BRIDGE_DIR="$ROOT_DIR/native/llama-bridge"
ARM_BUILD_DIR="$BRIDGE_DIR/build-arm64"
X64_BUILD_DIR="$BRIDGE_DIR/build-x64"
ARM_OUT="$ROOT_DIR/composeApp/src/jvmMain/resources/darwin-arm64"
X64_OUT="$ROOT_DIR/composeApp/src/jvmMain/resources/darwin-x64"
DEFAULT_VENDOR_DIR="$ROOT_DIR/third_party/llama.cpp"
LLAMA_CPP_REPO_URL="${LLAMA_CPP_REPO_URL:-https://github.com/ggml-org/llama.cpp.git}"
LLAMA_CPP_REF="${LLAMA_CPP_REF:-b8635075ffe27b135c49afb9a8b5c434bd42c502}"
CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/souz/vendor"
CACHED_LLAMA_DIR="$CACHE_ROOT/llama.cpp"
LLAMA_CPP_LOCAL_PATCH="$ROOT_DIR/native/llama-bridge/patches/llama.cpp-metal-bfloat-embed.patch"

mkdir -p "$ARM_OUT" "$X64_OUT"

ensure_cached_llama_dir() {
  if ! command -v git >/dev/null 2>&1; then
    echo "git is required to download llama.cpp. Install git or set LLAMA_CPP_SOURCE_DIR." >&2
    exit 1
  fi

  mkdir -p "$CACHE_ROOT"

  if [[ -e "$CACHED_LLAMA_DIR" && ! -d "$CACHED_LLAMA_DIR/.git" ]]; then
    echo "Cache path exists but is not a git checkout: $CACHED_LLAMA_DIR" >&2
    echo "Remove it manually or set LLAMA_CPP_SOURCE_DIR to a valid llama.cpp checkout." >&2
    exit 1
  fi

  if [[ ! -d "$CACHED_LLAMA_DIR/.git" ]]; then
    git clone "$LLAMA_CPP_REPO_URL" "$CACHED_LLAMA_DIR"
  fi

  git -C "$CACHED_LLAMA_DIR" fetch --depth 1 origin "$LLAMA_CPP_REF"
  git -C "$CACHED_LLAMA_DIR" checkout --detach "$LLAMA_CPP_REF"
}

resolve_llama_dir() {
  if [[ -n "${LLAMA_CPP_SOURCE_DIR:-}" ]]; then
    echo "$LLAMA_CPP_SOURCE_DIR"
    return
  fi

  if [[ -d "$DEFAULT_VENDOR_DIR" ]]; then
    echo "$DEFAULT_VENDOR_DIR"
    return
  fi

  ensure_cached_llama_dir
  echo "$CACHED_LLAMA_DIR"
}

apply_local_llama_patch() {
  if [[ ! -f "$LLAMA_CPP_LOCAL_PATCH" ]]; then
    return
  fi

  if git -C "$LLAMA_CPP_DIR" apply --reverse --check "$LLAMA_CPP_LOCAL_PATCH" >/dev/null 2>&1; then
    echo "llama.cpp local patch already applied: $(basename "$LLAMA_CPP_LOCAL_PATCH")"
    return
  fi

  echo "Applying local llama.cpp patch: $(basename "$LLAMA_CPP_LOCAL_PATCH")"
  git -C "$LLAMA_CPP_DIR" apply "$LLAMA_CPP_LOCAL_PATCH"
}

LLAMA_CPP_DIR="$(resolve_llama_dir)"
echo "Using llama.cpp from: $LLAMA_CPP_DIR"
echo "Pinned llama.cpp ref: $LLAMA_CPP_REF"
apply_local_llama_patch

cmake -S "$BRIDGE_DIR" -B "$ARM_BUILD_DIR" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DLLAMA_CPP_SOURCE_DIR="$LLAMA_CPP_DIR"

cmake --build "$ARM_BUILD_DIR" --config Release --target souz_llama_bridge -j
cp "$ARM_BUILD_DIR/bin/libsouz_llama_bridge.dylib" "$ARM_OUT/libsouz_llama_bridge.dylib"

cmake -S "$BRIDGE_DIR" -B "$X64_BUILD_DIR" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_ARCHITECTURES=x86_64 \
  -DGGML_NATIVE=OFF \
  -DLLAMA_CPP_SOURCE_DIR="$LLAMA_CPP_DIR"

cmake --build "$X64_BUILD_DIR" --config Release --target souz_llama_bridge -j
cp "$X64_BUILD_DIR/bin/libsouz_llama_bridge.dylib" "$X64_OUT/libsouz_llama_bridge.dylib"
