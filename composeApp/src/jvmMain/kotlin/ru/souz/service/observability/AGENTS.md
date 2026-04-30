## Project Structure
```text
observability/
├── DesktopStructuredLogging.kt       # Local structured telemetry logs, desktop telemetry sink, and chat observability tracker
└── AGENTS.md                         # This file
```

Notes:
- Observability events are written only to local rolling log files.
- `ChatUseCase` should delegate conversation/request bookkeeping here instead of owning extra observability state directly.
- The format uses SLF4J key-value pairs so the logs stay easy to parse and map into OpenTelemetry later.
- Retention is managed by `composeApp/src/jvmMain/resources/logback.xml`.
