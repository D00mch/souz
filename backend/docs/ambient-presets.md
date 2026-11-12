# Ambient presets

Set `SOUZ_FEATURE_AMBIENT_PRESETS=true` and configure `JEV_TOKEN` to enable
ready-made ambient backgrounds on Client-Souz channels. Use the Docker sandbox,
S3 attachment storage and the `AttachFileToReply` capability. Qwen credentials
(`QWEN_KEY` or the user's configured Qwen key) enable invented captions.

Install `ambient-background` v2 for each owner that should have access. The
bundle exposes a `preset` composite command with `preset` and `phrase` inputs.
The backend reads descriptions from `assets/ambient-presets.json` schema 1.
The skill renders the selected preset and attaches the HTML through the
ordinary attachment contract.

`ambient-background blue «Спокойной ночи»` selects a preset and a literal
caption directly. Natural-language requests use Jev for selection and Qwen
Flash once when the user asks to invent a caption. Custom scenes, requests
with input files, and unavailable presets use the regular agent. The
`thread.completed` payload and replay follow the existing WebSocket contract.
