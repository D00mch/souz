# Native Local Runtime

`native` is the JVM local-model module for Souz. It owns the Kotlin runtime under `ru.souz.llms.local` and the thin `llama.cpp` bridge used for on-device chat inference.

## Responsibilities

- Expose the local provider through `LocalChatAPI` and `LocalLlamaRuntime`.
- Detect whether local inference is available on the current host and which local profiles are eligible.
- Store and download GGUF models under `~/.local/state/souz/models`.
- Extract packaged bridge binaries under `~/.local/state/souz/native/`.
- Render prompts and normalize local-model output into the shared `:llms` chat/tool-calling contract.
- Load the packaged native bridge library shipped from `native/src/main/resources/darwin-*`.

## Main components

- `LocalLlamaRuntime` manages runtime creation, model loading, preload warmup, generation, cancellation, and fallback retries.
- `LocalBridgeLoader` and `LocalNativeBridge` load `libsouz_llama_bridge.dylib` through JNA and map the bridge C API.
- `LocalModels` and `LocalModelStore` define supported local profiles, host/platform gating, and model download/storage rules.
- `LocalPromptRenderer` and `LocalStrictJsonParser` keep local inference compatible with the shared request/response and tool-call DTOs.

## Native bridge

- Native sources live in `llama-bridge/`.
- `third_party/llama.cpp` and `llama-bridge/build-*` are local-only paths and should stay untracked.
- Packaged bridge binaries live in `native/src/main/resources/darwin-*`.
- Rebuild packaged binaries with `composeApp/src/jvmMain/resources/scripts/build-llama-bridge.sh`.
- On macOS the bridge disables ggml Metal residency sets by default with `GGML_METAL_NO_RESIDENCY=1`; set `SOUZ_LLAMA_METAL_RESIDENCY=1` only when you need to opt back in for debugging.

## Boundaries

- `:native` depends on `:llms` for shared DTOs and provider-facing interfaces.
- `composeApp` depends on `:native` for local inference and mirrors the module-owned bridge binaries into packaged app resources for macOS distribution.
- Keep UI, app wiring, and platform packaging concerns out of this module.

## Tests

- `LocalInferenceSupportTest` covers prompt rendering, strict JSON parsing, availability checks, and local runtime helpers.
- Run it with:

```bash
./gradlew :native:test --tests ru.souz.local.LocalInferenceSupportTest
```

## Structure

See [AGENTS.md](AGENTS.md) for the module file tree and local maintenance notes.
