# Building libMediaKeys.dylib

Rebuild the JNI library on macOS with the Xcode toolchain:

```bash
swiftc -emit-library -target x86_64-apple-macosx13.0 \
  ../swift/MediaKeys.swift MediaKeysJNI.c \
  -o libMediaKeys.dylib
```

This compiles the Swift media key handler and the JNI bridge into
`libMediaKeys.dylib`.
