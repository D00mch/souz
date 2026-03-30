## Project Structure
```text
local/
├── LocalBridge.kt                  # JNA bridge loader/native wrapper for the bundled llama.cpp runtime
├── LocalChatAPI.kt                 # `GigaChatAPI` adapter that routes chat requests to the local runtime
├── LocalLlamaRuntime.kt            # Runtime lifecycle, model load/unload, warmup, generation, retry, and cancel logic
├── LocalModelSelection.kt          # UI-facing download prompt/state helpers for missing local models
├── LocalModelStore.kt              # Local model presence checks and authenticated download/store flow
├── LocalModels.kt                  # Local model profiles, host/platform detection, and provider availability checks
├── LocalPromptRenderer.kt          # Qwen prompt rendering, tool guidance compaction, and tool-result truncation
├── LocalStrictJson.kt              # Strict JSON contract plus tolerant parser/recovery for local model output
└── INFO.md                         # This file
```

Notes:
- This package is the local-LLM provider stack. `LocalChatAPI` exposes chat/chatStream through the shared `GigaChatAPI` interface, while embeddings, file upload/download, and balance remain unsupported for local models in this version.
- `LocalProviderAvailability` currently supports only macOS `arm64` and `x64`. It gates local inference on both bridge availability and RAM-based profile selection; the current built-in profile is `Local Qwen3 4B Instruct 2507` with minimum `8` GB RAM.
- `LocalBridgeLoader` extracts the bundled native bridge to `~/.local/state/souz/native/<platform>/` and `LocalModelStore` keeps GGUF files under `~/.local/state/souz/models/<profile-id>/`. Model downloads use Java `HttpClient`, follow redirects, send `HF_TOKEN` or `HUGGING_FACE_HUB_TOKEN` when present, stream into `*.part`, then atomically move into place.
- `LocalLlamaRuntime` keeps a single native runtime and one loaded model at a time, guarded by `loadMutex` and `runtimeOperationMutex`. `preload()` loads the selected model and performs a one-token warmup request once per model id; `close()`/`shutdown()` unload the model, destroys the runtime, and cancels in-flight work.
- Generation defaults in `LocalLlamaRuntime`:
    - completion budget is capped at `1024` tokens
    - context is bucketed across `2048, 4096, 6144, 8192, 12288, 16384` and clamped by the selected profile max context
    - retries once without native grammar if grammar initialization fails, and can retry with the profile max context when the prompt does not fit
- `LocalPromptRenderer` always renders Qwen chat separators. It injects `LocalStrictJsonContract` instructions by default, but switches to plain-text mode for classification prompts that contain the `CATEGORY1,CATEGORY2 0-100` marker. Tool guidance is compressed from full to compact/minimal signatures as the active toolset grows, and oversized tool results are truncated to a preview to stay within local context budgets.
- `LocalStrictJsonParser` accepts the strict `{"type":"final"...}` / `{"type":"tool_calls"...}` contract, but also recovers common local-model failures such as control-token wrappers, plain-text final answers, single-call objects, embedded `{"result":...}` payloads, and malformed `tool_calls` polluted by schema fields.
- Main regression coverage for this package is in `composeApp/src/jvmTest/kotlin/ru/souz/local/LocalInferenceSupportTest.kt`, with additional integration coverage in `composeApp/src/jvmTest/kotlin/ru/souz/ui/main/MainViewModelTest.kt`, `composeApp/src/jvmTest/kotlin/ru/souz/ui/settings/SettingsViewModelTest.kt`, and `composeApp/src/jvmTest/kotlin/ru/souz/giga/LLMFactoryEmbeddingsTest.kt`.
