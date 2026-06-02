# Ambient Agent

Souz ambient agent is a local-first proactive-help pipeline. It listens only after the user clicks the `Souz`/`Союз` title, keeps ambient data in memory, proposes bounded in-window suggestions, and sends confirmed tasks through the normal desktop agent path.

## Stage 1: Ambient Transcription

- Uses `AmbientTranscriptionService` in `:sharedLogic`.
- Prefers `LiveSpeechTranscriptionProvider`; when local live STT is unsupported, ambient falls back to local microphone batch STT.
- Captures local microphone PCM frames through desktop audio wiring.
- Keeps transcripts only in an ephemeral `AmbientTranscriptBuffer`.
- Does not persist raw audio, transcripts, or proposals.

## Stage 2/3: Semantic Blocks And Local Analysis

- Lives in `:ambientAgent`.
- `SemanticBlockBuilder` turns final transcript events into deterministic `AmbientSemanticBlock` values.
- Volatile transcript hypotheses are ignored for block creation.
- Blocks are closed on pause, max duration, max chars, stop, or manual flush. Live transcript events flush after 1 second of inactivity; batch fallback events wait one more batch window and may be grouped for up to 10 seconds so split recognition chunks form a single thought.
- Speaker role is heuristic only: `PROBABLY_USER`, `UNKNOWN`, `PROBABLY_OTHER`, or `BACKGROUND_MEDIA`.
- Addressedness is heuristic only: direct Souz address, implicit user intent, ambient conversation, background/quoted speech, or unknown.
- `AmbientBlockAnalyzer` sends closed blocks to a local-only model through `AmbientLocalLlm`.
- Analyzer output is compact text parsed into extracted task candidates.
- Task candidates are proposals only and always require confirmation.
- `AmbientAnalysisService` keeps recent analyses and candidates in memory only.
- `AmbientAnalysisPipeline` connects semantic blocks to analysis with a small bounded queue.

## Capability Manifest

- Desktop wiring exposes visible tool metadata through `AgentToolAmbientCapabilityProvider`.
- Tools are filtered through `AgentToolsFilter` before the manifest is built.
- The prompt manifest is category-level: one compact `capability|CATEGORY|heard=...|task=...` line per available tool category.
- Tool ids use `tool:<category>:<functionName>`.
- Obvious read/list/search/calculator tools are low risk; create/update/draft/open-style tools are medium risk; delete/modify/send/shell/screen-recording/destructive tools are high risk.
- Tools are never executed by ambient analysis.
- Skill capabilities currently use `EmptyAmbientSkillCapabilityProvider`; read-only skill capability listing is a follow-up.

## Suggestion Pipeline

- `AmbientAnalysisService.taskCandidates` feeds `AmbientSuggestionPipeline`.
- `AmbientSuggestionController` applies confidence, addressedness, and high-risk gates. Capability ids are prompt context for the ambient model, not a required part of the suggestion.
- `InMemoryAmbientSuggestionStore` keeps suggestions in memory only, limits pending suggestions to three, expires pending suggestions after ten seconds, and dedupes repeated task/evidence pairs for a short cooldown.
- The main window renders at most one primary suggestion card near the chat input with a `+N` overflow count.
- The card has explicit `Сделать` and `Не сейчас` actions.
- Confirmed suggestions call `ChatUseCase.sendChatMessage` with `isVoice=true` and `ChatRequestSource.AMBIENT_AGENT`.
- The main agent still performs tool selection, safe-mode checks, permission prompts, modify review, and speech/TTS response handling.

## UI Toggle

- Ambient mode does not auto-start.
- Clicking the `Souz`/`Союз` word in the window title toggles ambient mode.
- Active ambient mode uses a soft gold title pulse; startup uses static gold and errors use a muted red tint.
- Turning ambient mode off stops microphone transcription, semantic/analysis collection, suggestion collection, clears the volatile transcript, and clears pending suggestions.

## Current Limits

- Ambient analysis never executes tools or skills directly.
- No auto-execution without user confirmation.
- No memory writes.
- Diarization or speaker identification.
- Wake word.
- Dedicated VAD/SpeechDetector.
- Long-term transcript/proposal storage.
- Cloud STT or cloud LLM fallback.
