#!/usr/bin/env bash
set -e

LIB_DIR="src/main/libs"
SWIFT_SRC="src/main/swift/MediaKeys.swift"
JNI_SRC="src/main/libs/MediaKeysJNI.c"
LIB_NAME="libMediaKeys.dylib"

mkdir -p "$LIB_DIR"

# Compile the JNI bridge
cc -c "$JNI_SRC" -fPIC \
  -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/$(uname | tr '[:upper:]' '[:lower:]')" \
  -o "$LIB_DIR/MediaKeysJNI.o"

# Build Swift and link with the JNI object into a dylib
swiftc "$SWIFT_SRC" "$LIB_DIR/MediaKeysJNI.o" \
  -emit-library -o "$LIB_DIR/$LIB_NAME"

rm "$LIB_DIR/MediaKeysJNI.o"

printf "Built %s\n" "$LIB_DIR/$LIB_NAME"