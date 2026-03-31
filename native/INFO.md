# Native

## Project Structure

```text
native/
├── src/main/kotlin/ru/souz/llms/local/
│   ├── LocalBridge.kt                 # JNA loader plus JVM-side bridge calls into the native library
│   ├── LocalChatAPI.kt                # Local provider adapter that implements the shared chat API
│   ├── LocalLlamaRuntime.kt           # Runtime lifecycle, model loading, preload, generation, and cancellation
│   ├── LocalModelSelection.kt         # Download prompt/state helpers for local model selection flows
│   ├── LocalModels.kt                 # Local model catalog, host/platform detection, and availability gating
│   ├── LocalModelStore.kt             # Model storage paths and GGUF download support
│   ├── LocalPromptRenderer.kt         # Qwen prompt rendering and compact tool-guidance generation
│   └── LocalStrictJson.kt             # Strict JSON contract plus recovery/parsing for local model output
├── src/test/kotlin/ru/souz/local/
│   └── LocalInferenceSupportTest.kt   # Coverage for prompt rendering, parsing, availability, and local runtime helpers
├── llama-bridge/
│   ├── CMakeLists.txt                 # Native bridge build definition against llama.cpp
│   ├── include/souz_llama_bridge.h    # C ABI exported to the JVM bridge loader
│   └── src/souz_llama_bridge.cpp      # llama.cpp-backed runtime, model, and generation bridge
├── build.gradle.kts                   # Local-model runtime module build
└── INFO.md                            # Local runtime and native bridge notes
```

Notes:
- `:native` is a JVM Gradle module that owns the Kotlin local-model runtime under `native/src/main/kotlin/ru/souz/llms/local`.
- `composeApp` depends on this module for local inference, but packaged bridge binaries still live in `composeApp/src/jvmMain/resources/darwin-*`.
- `third_party/llama.cpp` and `native/llama-bridge/build-*` are local-only paths and should stay untracked.
- Treat those paths as out of scope unless the task is explicitly about updating upstream `llama.cpp` or debugging the native bridge build.
- Packaged bridge binaries live in `composeApp/src/jvmMain/resources/darwin-*`.
- Rebuild the packaged bridge binaries with `composeApp/src/jvmMain/resources/scripts/build-llama-bridge.sh`.
- The rebuild script uses `LLAMA_CPP_SOURCE_DIR` when set, otherwise a local `third_party/llama.cpp` checkout, otherwise it clones the pinned `llama.cpp` ref `968189729f71bf1dbe109556986ddf2e2cf3e534` into `${XDG_CACHE_HOME:-~/.cache}/souz/vendor/llama.cpp`.
- On macOS the bridge now disables ggml Metal residency sets by default (`GGML_METAL_NO_RESIDENCY=1`) to avoid shutdown aborts; set `SOUZ_LLAMA_METAL_RESIDENCY=1` only if you need to opt back in for debugging.
