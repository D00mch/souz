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
- If `Local MacOS STT` is selected, Souz uses only the local macOS recognizer and never falls back to cloud STT.
- If a cloud provider is selected and is unavailable, routing falls back across enabled providers in this order: OpenAI, AiTunnel, Salute Speech.
- Among enabled providers, Souz prefers one with a configured key. If none has a key, the first enabled provider is still chosen and fails with that provider's missing-key error.
- Settings expose cloud voice recognition models only when the matching API key is configured, while `Local MacOS STT` is shown on macOS without keys. Invalid saved selections are normalized.

## Audio Packaging

- Recorded audio is raw PCM: `16 kHz`, mono, `16-bit`.
- Salute Speech receives that PCM directly with `Content-Type: audio/x-pcm;bit=16;rate=16000`.
- OpenAI and AiTunnel wrap the same PCM bytes into a WAV file before upload:
  - filename: `capture.wav`
  - media type: `audio/wav`
- `Local MacOS STT` reuses the same PCM capture, writes it to a temporary WAV file on the JVM side, and transcribes that file with `SFSpeechURLRecognitionRequest`.

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
  - explicit local errors are surfaced for denied permission, unavailable recognizer, and unsupported locale
