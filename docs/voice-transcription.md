# Voice Transcription Pipeline

This document summarizes provider selection, audio packaging, and endpoint-specific constraints for speech-to-text.

## Routing

- Voice transcription is routed by `ModelAwareSpeechRecognitionProvider`.
- The selected `voiceRecognitionModel` picks the preferred provider:
  - `SaluteSpeech` -> Salute Speech
  - `AI-Tunnel:*` -> AiTunnel
  - `OpenAI:*` -> OpenAI
  - `Local MacOS STT` -> macOS `Speech` framework via the local Swift/JNI bridge
- Provider gating:
  - Salute Speech: RU profile only
  - OpenAI transcription: EN profile only
  - AiTunnel transcription: RU profile/build only
- `Local MacOS STT` is exposed only on macOS and does not require any API key.
- If `Local MacOS STT` is selected, Souz uses only local macOS STT backends and never falls back to cloud STT.
- If a cloud provider is selected and is unavailable, routing falls back across enabled providers in this order: OpenAI, AiTunnel, Salute Speech.
- Among enabled providers, Souz prefers one with a configured key. If none has a key, the first enabled provider is still chosen and fails with that provider's missing-key error.
- Settings expose cloud voice recognition models only when the matching API key is configured, while `Local MacOS STT` is shown on macOS without keys. Invalid saved selections are normalized.

## Audio Packaging

- Recorded audio is raw PCM: `16 kHz`, mono, `16-bit`.
- Salute Speech receives that PCM directly with `Content-Type: audio/x-pcm;bit=16;rate=16000`.
- OpenAI and AiTunnel wrap the same PCM bytes into a WAV file before upload:
  - filename: `capture.wav`
  - media type: `audio/wav`
- `Local MacOS STT` reuses the same PCM capture and auto-selects the best local macOS backend:
  - SpeechAnalyzer live backend: feeds PCM chunks directly to `SpeechAnalyzer` / `SpeechTranscriber`.
  - Legacy batch backend: writes PCM to a temporary WAV file and transcribes it with `SFSpeechURLRecognitionRequest`.
- The legacy batch backend is limited to 45 seconds of `16 kHz` mono `16-bit` PCM. The SpeechAnalyzer live backend does not apply this legacy batch limit.

## Endpoint Constraints

- Salute Speech:
  - `POST https://smartspeech.sber.ru/rest/v1/speech:recognize`
  - raw PCM request body
- OpenAI:
  - `POST https://api.openai.com/v1/audio/transcriptions`
  - multipart upload with `model` and `file`
  - model resolution: selected alias -> `OPENAI_TRANSCRIPTION_MODEL` env -> same JVM property -> `gpt-4o-transcribe`
- AiTunnel:
  - `POST https://api.aitunnel.ru/v1/audio/transcriptions`
  - multipart upload with `file`, `model`, and `language`
  - model resolution: selected alias -> `AITUNNEL_TRANSCRIPTION_MODEL` env -> same JVM property -> `gpt-4o-transcribe`
  - language resolution: `AITUNNEL_TRANSCRIPTION_LANGUAGE` env -> same JVM property -> `ru`
- Local MacOS STT:
  - JVM side requests Speech authorization lazily on first use
  - locale resolution: `ru` -> `ru-RU`, `en` -> `en-US`
  - auto selection keeps the user-facing provider as `Local MacOS STT`
  - on supported macOS versions, Souz prefers the SpeechAnalyzer live backend
  - if the live backend is unavailable before transcription because of unsupported OS, unsupported locale, missing native live symbols, or missing model assets, push-to-talk falls back to the legacy local batch backend
  - permission errors are surfaced and are not silently downgraded to another backend
  - cloud recognition fallback is not allowed
  - audio should stay local to the macOS Speech backend and must not be sent over the network by this provider
  - explicit local errors are surfaced for denied permission, unavailable recognizer, unsupported locale, and missing on-device support

## Local MacOS STT Backends

### Auto Selection

- User-facing provider remains `Local MacOS STT`.
- On supported macOS versions, Souz prefers the SpeechAnalyzer live backend.
- If the live backend is unavailable before transcription starts, push-to-talk falls back to the legacy local batch backend.
- No cloud fallback is allowed for `Local MacOS STT`.

### SpeechAnalyzer Live Backend

- Requires macOS 26+ and supported runtime/native symbols.
- Uses `SpeechAnalyzer` with `SpeechTranscriber`.
- Accepts live PCM chunks through `start`, `acceptPcm`, `pollEvents`, `finalizeAndFinish`, and `cancel`.
- Emits volatile and final transcript events.
- Does not persist raw audio.
- Intended as the backend for future ambient listening.
- Future `AmbientMicListener` work should require this backend and must not fall back to the legacy batch backend.

### Legacy Batch Backend

- Uses `SFSpeechURLRecognitionRequest`.
- Sets `requiresOnDeviceRecognition = true`.
- Returns final-only text.
- Writes a temporary WAV file on the JVM side and deletes it after recognition.
- Keeps the 45 second PCM limit.
- Used as a local push-to-talk fallback only.

### Not Implemented In This Change

- No ambient listener.
- No VAD or `SpeechDetector`.
- No diarization or speaker identification.
- No intent detection.
- No suggestion UI.
- No automatic task execution.
