# Observability

## Invariants

- `ChatObservabilityTracker` owns conversation/request bookkeeping and closure coordination. Callers such as `ChatUseCase` delegate to it instead of maintaining parallel counters or lifecycle state.
- `DesktopStructuredLogger` and `StructuredLoggingAgentTelemetry` emit structured SLF4J key-value events. Preserve the event domain/name fields and request context needed to correlate app sessions, conversations, requests, and tool calls.
- The shared layer emits events but does not own a logging backend, destination, or retention policy. The desktop host routes the structured logger to bounded local telemetry files through its Logback configuration.
- Provider adapters do not copy a serialized function-call envelope into `LLMResponse.Message.content`. Tool-call history is reconstructed from `functionCall`; non-empty content is treated as assistant text by the streaming runtime.

## Why this is fragile

Requests can finish after a conversation-close request, so the tracker defers final closure until active work drains. Splitting that state across UI and runtime layers can double-count requests, lose tool totals, or finish a conversation before its last request is recorded.

Structured fields are consumed as machine-readable metadata. Changing field names or replacing key-value logging with formatted prose silently breaks downstream parsing. Tool telemetry currently records names, argument key names/counts, outcomes, and timing; adding argument values or user content requires an explicit privacy decision.

Duplicating a typed function call into message content exposes its serialized JSON as a transient assistant-text delta. The pending UI/backend response displays that delta until the completed turn replaces it.

## Safe changes

- Carry the request execution context through coroutine context so agent tool events correlate with the request.
- Update lifecycle transitions and aggregate metrics in the tracker as one coherent state change.
- Keep sink configuration, file paths, rollover, and retention in the host rather than `:sharedLogic`.
- Add fields compatibly and keep high-cardinality or sensitive payload content out of structured events by default.
- When adapting a provider's tool-call response, populate `functionCall` and its call ID without copying the call envelope into `content`.

## Verification

Run:

```zsh
./gradlew :sharedLogic:jvmTest --tests 'ru.souz.service.observability.*'
./gradlew :sharedLogic:jvmTest --tests 'ru.souz.llms.CodexChatAPIRequestTest'
```

Test overlapping requests, deferred conversation closure, request status paths, usage aggregation, tool totals, and bounded desktop retention when those behaviors change.
