#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
BUILD_DIR="$PROJECT_DIR/composeApp/build/macos-speech-bridge"
SWIFT_SRC="$PROJECT_DIR/composeApp/src/jvmMain/swift/MacOsSpeechBridge.swift"
JNI_SRC="$PROJECT_DIR/composeApp/src/jvmMain/swift/MacOsSpeechBridgeJNI.c"
OUT_ARM64="$PROJECT_DIR/composeApp/src/jvmMain/resources/darwin-arm64"
OUT_X64="$PROJECT_DIR/composeApp/src/jvmMain/resources/darwin-x64"
LIB_NAME="libsouz_macos_speech_bridge.dylib"
ARM64_DIR="$BUILD_DIR/arm64"
X64_DIR="$BUILD_DIR/x64"
MODULE_CACHE_DIR="$BUILD_DIR/module-cache"

if [ -z "${DEVELOPER_DIR:-}" ] && [ -d "/Applications/Xcode.app/Contents/Developer" ]; then
  export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
fi
JAVA_HOME_VALUE="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home)}"
SDK_PATH="$(xcrun --sdk macosx --show-sdk-path)"

mkdir -p "$BUILD_DIR" "$ARM64_DIR" "$X64_DIR" "$MODULE_CACHE_DIR" "$OUT_ARM64" "$OUT_X64"

xcrun clang -c "$JNI_SRC" \
  -fPIC \
  -arch arm64 \
  -isysroot "$SDK_PATH" \
  -mmacosx-version-min=12.0 \
  -I"$JAVA_HOME_VALUE/include" \
  -I"$JAVA_HOME_VALUE/include/darwin" \
  -o "$ARM64_DIR/MacOsSpeechBridgeJNI.o"

SWIFT_MODULE_CACHE_PATH="$MODULE_CACHE_DIR" CLANG_MODULE_CACHE_PATH="$MODULE_CACHE_DIR" \
  xcrun swiftc "$SWIFT_SRC" "$ARM64_DIR/MacOsSpeechBridgeJNI.o" \
  -emit-library \
  -target arm64-apple-macos12.0 \
  -sdk "$SDK_PATH" \
  -framework Foundation \
  -framework Speech \
  -o "$ARM64_DIR/$LIB_NAME"

xcrun clang -c "$JNI_SRC" \
  -fPIC \
  -arch x86_64 \
  -isysroot "$SDK_PATH" \
  -mmacosx-version-min=12.0 \
  -I"$JAVA_HOME_VALUE/include" \
  -I"$JAVA_HOME_VALUE/include/darwin" \
  -o "$X64_DIR/MacOsSpeechBridgeJNI.o"

SWIFT_MODULE_CACHE_PATH="$MODULE_CACHE_DIR" CLANG_MODULE_CACHE_PATH="$MODULE_CACHE_DIR" \
  xcrun swiftc "$SWIFT_SRC" "$X64_DIR/MacOsSpeechBridgeJNI.o" \
  -emit-library \
  -target x86_64-apple-macos12.0 \
  -sdk "$SDK_PATH" \
  -framework Foundation \
  -framework Speech \
  -o "$X64_DIR/$LIB_NAME"

lipo -create \
  "$ARM64_DIR/$LIB_NAME" \
  "$X64_DIR/$LIB_NAME" \
  -output "$BUILD_DIR/$LIB_NAME"

cp "$BUILD_DIR/$LIB_NAME" "$OUT_ARM64/$LIB_NAME"
cp "$BUILD_DIR/$LIB_NAME" "$OUT_X64/$LIB_NAME"

file "$OUT_ARM64/$LIB_NAME"
file "$OUT_X64/$LIB_NAME"

printf "Built %s\n" "$LIB_NAME"
