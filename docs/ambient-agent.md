# Ambient Agent

Souz ambient agent is currently a local-first foundation for future proactive help. It does not execute tasks, write memory, show suggestion cards, or start listening automatically.

## Stage 1: Ambient Transcription

- Uses `AmbientTranscriptionService` in `:sharedLogic`.
- Requires `LiveSpeechTranscriptionProvider`; legacy batch STT and cloud STT are not used for ambient mode.
- Captures local microphone PCM frames through desktop audio wiring.
- Keeps transcripts only in an ephemeral `AmbientTranscriptBuffer`.
- Does not persist raw audio, transcripts, or proposals.

## Stage 2/3: Semantic Blocks And Local Analysis

- Lives in `:ambientAgent`.
- `SemanticBlockBuilder` turns final transcript events into deterministic `AmbientSemanticBlock` values.
- Volatile transcript hypotheses are ignored for block creation.
- Blocks are closed on pause, max duration, max chars, stop, or manual flush.
- Speaker role is heuristic only: `PROBABLY_USER`, `UNKNOWN`, `PROBABLY_OTHER`, or `BACKGROUND_MEDIA`.
- Addressedness is heuristic only: direct Souz address, implicit user intent, ambient conversation, background/quoted speech, or unknown.
- `AmbientBlockAnalyzer` sends closed blocks to a local-only model through `AmbientLocalLlm`.
- Analyzer output is strict JSON parsed into extracted statements and task candidates.
- Task candidates are proposals only and always require confirmation.
- `AmbientAnalysisService` keeps recent analyses and candidates in memory only.
- `AmbientAnalysisPipeline` connects semantic blocks to analysis with a small bounded queue.

## Capability Manifest

- Desktop wiring exposes visible tool metadata through `AgentToolAmbientCapabilityProvider`.
- Tools are filtered through `AgentToolsFilter` before the manifest is built.
- The manifest contains ids, names, categories, compact descriptions, and short examples.
- Tools are never executed by ambient analysis.
- Skill capabilities currently use `EmptyAmbientSkillCapabilityProvider`; read-only skill capability listing is a follow-up.

## Not Implemented Yet

- Suggestion UI.
- Confirmation buttons.
- Execution through the main agent.
- TTS replies.
- Tool or skill invocation.
- Memory writes.
- Diarization or speaker identification.
- Wake word.
- Dedicated VAD/SpeechDetector.
- Long-term transcript/proposal storage.
