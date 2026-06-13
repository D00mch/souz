# Ambient Agent

Souz ambient agent is a local-first proactive-help MVP. It listens only after the user enables ambient mode, keeps ambient data volatile in memory, proposes bounded in-window suggestions, and sends confirmed tasks through the normal desktop agent path.

## Pipeline

- `AmbientTranscriptionService` lives in `:ambientAgent`; generic speech providers remain in `:sharedLogic`.
- Live STT is preferred. If local live STT is unavailable, the existing batch fallback still records short PCM windows and recognizes them through the configured speech provider.
- Transcript events are streamed only. There is no transcript snapshot, retained transcript history, or debug transcript buffer.
- `SemanticBlockBuilder` turns transcript events into `AmbientSemanticBlock` values and emits closed blocks through a Flow. Semantic block history/snapshots are not retained.
- `LocalLlmAmbientBlockAnalyzer` uses a compact text protocol only: `EMPTY` or `TASK: <short natural-language task>`. It does not use JSON, capability manifests, tool ids, slots, risk labels, explanations, or raw-output previews.

## Suggestions

- Ambient analysis proposes at most one task per semantic block.
- Suggestions are pending-only, capped to three, expire after ten seconds, and are deduped by strict normalized task text for a short cooldown.
- Accepting a suggestion sends `candidate.taskText` through `ChatUseCase.sendChatMessage` with `isVoice=true` and `ChatRequestSource.AMBIENT_AGENT`.
- Rejecting, dismissing, consuming, or expiring a suggestion removes it from memory. No rejected/completed/failed suggestion history is retained.

## Privacy And Limits

- Ambient data is volatile and in-memory only.
- No raw transcript text, prompt text, raw model output, or task candidate text should be logged from the ambient flow. Logs may include ids, source, final flag, char counts, status, and sanitized exception metadata.
- Ambient analysis never executes tools or skills directly.
- Ambient analysis never writes memory.
- User confirmation is required before the main agent receives a task.
- While ambient mode is starting or active, normal push-to-talk voice input is disabled because both paths share the microphone. No auto-pause/resume policy is implemented.
