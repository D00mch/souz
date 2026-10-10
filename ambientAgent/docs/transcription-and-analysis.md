# Transcription and analysis

## Invariant

Ambient audio is volatile 16 kHz, mono, 16-bit PCM. The service prefers live speech recognition and falls back to configured batch recognition when live startup is unavailable. It publishes bounded in-memory transcript events and never persists microphone audio.

Live events are cumulative hypotheses: newer volatile text supersedes the open live hypothesis, and text already closed into a block must be removed from later cumulative results. Batch fallback events are independent final windows and may remain in a semantic block longer than live events.

Local analysis only proposes a task. The parser accepts `EMPTY`, or a response containing exactly one line whose trimmed prefix is `TASK:`. Other non-fenced lines are currently tolerated; multiple task lines, fenced output, and nonblank, non-`EMPTY` responses without a task line are rejected. A deterministic fallback may reuse block text only for direct or implicit user intent. Dispatch remains in `:sharedUI` and occurs only after the user accepts a bounded, unexpired suggestion.

## Why this is fragile

Treating cumulative live hypotheses as independent events duplicates speech across blocks. Applying live-window timing to batch results fragments sentences. Blocking transcript emission can stall microphone processing, while unbounded buffering retains sensitive speech and increases latency. Dispatching from analysis would bypass the product's confirmation boundary.

## Safe changes

- Keep PCM validation before opening the microphone or recognition session.
- Preserve `source`, `isFinal`, and timestamps; batch non-final events are invalid, while live non-final events replace the open hypothesis.
- Keep transcript and controller buffers bounded and tolerate drops, repeated finals, blanks, missing timestamps, and no-speech batch windows.
- Reset cumulative-live baselines on clear, and discard the open baseline around confirmed-task execution so the accepted phrase is not proposed again.
- Keep task text and prompts bounded, propagate coroutine cancellation, and return no candidate on analysis failures.
- Do not execute a candidate in this module. Suggestion expiry, deduplication, confirmation, and normal-agent dispatch belong to the UI controller.

## Verification

Run `./gradlew :ambientAgent:jvmTest` and the relevant `:sharedUI:jvmTest` controller tests when orchestration semantics change. Cover live replacement/delta handling, batch grouping, fallback startup, cancellation during start/stop, buffer overflow tolerance, single-task-line analyzer parsing, and confirmation-only dispatch.
