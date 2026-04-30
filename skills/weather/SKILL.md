---
name: weather
description: Fetch current weather and a short forecast for a location. Weather and forecast helper. Погода и прогноз для города или региона.
when_to_use: Use when the user asks about weather, forecast, temperature, rain, or wind. Also use for запросы про погоду, прогноз, температуру, дождь или ветер.
disable-model-invocation: false
user-invocable: true
allowed-tools:
  - Bash
metadata:
  openclaw:
    requires:
      bins:
        - curl
    os:
      - linux
      - macos
---
# Weather

Use this skill when the user wants practical weather data for a city, region, or coordinates.

Guidelines:
- Use `RunBashCommand` for the external fetch. Do not invent weather values.
- Prefer `wttr.in` for a quick human-readable answer.
- If the result is missing, ambiguous, or too terse, fall back to Open-Meteo with a geocoding lookup.
- Keep the final answer concise: current conditions first, then a short forecast or caveat.

Suggested commands:

`wttr.in` quick check:
```bash
curl -fsSL "https://wttr.in/<location>?format=3"
```

`wttr.in` detailed view:
```bash
curl -fsSL "https://wttr.in/<location>?format=j1"
```

Open-Meteo fallback flow:
1. Geocode the place name with `https://geocoding-api.open-meteo.com/v1/search?name=<location>&count=1&language=en&format=json`
2. Extract latitude and longitude from the first result.
3. Fetch forecast with `https://api.open-meteo.com/v1/forecast?...`

Rules:
- Treat the markdown examples as guidance only. Execute commands only through `RunBashCommand`.
- If the user did not provide a location, ask for one.
- If multiple places match, say which place you used or ask a clarifying question.
