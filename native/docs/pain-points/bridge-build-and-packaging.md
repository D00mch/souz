# Bridge build and packaging

## Invariant

`third_party/llama.cpp` and `native/llama-bridge/build-*` are local build inputs and must stay untracked. The bridge source, local upstream patch, C header, and packaged macOS dylibs are repository assets. Desktop packaging consumes the dylibs from `native/src/main/resources/darwin-*`.

The canonical rebuild script is `desktopApp/src/main/resources/scripts/build-llama-bridge.sh`. It resolves `llama.cpp` from `LLAMA_CPP_SOURCE_DIR`, then the local vendor checkout, then a cached checkout at the ref declared by the script; it also applies the tracked compatibility patch. Treat the script as the source of truth instead of copying its ref into documentation.

## Why this is fragile

The JVM JNA declarations, exported C header, C++ implementation, upstream `llama.cpp` API, and two packaged architectures form one ABI. Updating only source code or one dylib can pass Kotlin compilation but fail on another host or in the packaged desktop app.

The macOS bridge disables Metal residency sets by default to avoid shutdown failures. Enabling them globally can reintroduce process-exit crashes.

## Safe changes

- Keep the JNA interface, C header, and C++ exports synchronized in name, argument order, ownership, and error handling.
- Change the upstream ref or patch only in the build inputs, then rebuild both architectures through the script.
- Do not commit the vendor checkout or build directories. Review binary changes as expected outputs of a source/ABI change.
- Preserve `GGML_METAL_NO_RESIDENCY=1` as the default before library load. Use `SOUZ_LLAMA_METAL_RESIDENCY=1` only as an explicit debugging override.
- Keep returned native strings paired with the bridge free function and keep cancellation wired through the runtime handle.

## Verification

Run the rebuild script, then `./gradlew :native:test`. Confirm both packaged dylibs were produced for their declared architecture and that no vendor or build directory became tracked. Exercise bridge healthcheck, model load/unload, generation cancellation, and embeddings when the ABI changes.
