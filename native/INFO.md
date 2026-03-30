# Native

## Local Native Bridge

- `third_party/llama.cpp` and `native/llama-bridge/build-*` are local-only paths and should stay untracked.
- Treat those paths as out of scope unless the task is explicitly about updating upstream `llama.cpp` or debugging the native bridge build.
- Packaged bridge binaries live in `composeApp/src/jvmMain/resources/darwin-*`.
- Rebuild the packaged bridge binaries with `composeApp/src/jvmMain/resources/scripts/build-llama-bridge.sh`.
- The rebuild script uses `LLAMA_CPP_SOURCE_DIR` when set, otherwise a local `third_party/llama.cpp` checkout, otherwise it clones the pinned `llama.cpp` ref `968189729f71bf1dbe109556986ddf2e2cf3e534` into `${XDG_CACHE_HOME:-~/.cache}/souz/vendor/llama.cpp`.
- On macOS the bridge now disables ggml Metal residency sets by default (`GGML_METAL_NO_RESIDENCY=1`) to avoid shutdown aborts; set `SOUZ_LLAMA_METAL_RESIDENCY=1` only if you need to opt back in for debugging.
