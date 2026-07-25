# Ambient Agent

Before changing this module, read its [pain-point index](docs/pain-points.md) and the topics relevant to the change.

## Purpose and boundaries

- `:ambientAgent` owns portable ambient transcript, semantic-block, analysis, candidate, and suggestion contracts plus the JVM transcription service.
- Microphone capture adapters belong to hosts. Speech-provider contracts and live/batch implementations live in `:sharedLogic` `jvmMain`. UI lifecycle, suggestion confirmation, and dispatch through the main agent belong to `:sharedUI`.
- Keep common ambient models independent of Compose and desktop APIs; JVM-only provider integration stays in `jvmMain`.

## Invariants

- The transcription service accepts 16 kHz, mono, 16-bit PCM and owns only volatile in-memory state.
- Live hypotheses are cumulative and may be non-final; batch fallback emits independent final windows. Preserve source and finality semantics when building blocks.
- Local analysis proposes at most one bounded task using the `EMPTY` / `TASK:` protocol. It must never dispatch or execute work.
- Preserve coroutine cancellation and bounded transcript buffering; consumers must tolerate dropped or superseded hypotheses.

## Verification

```bash
./gradlew :ambientAgent:jvmTest
```
