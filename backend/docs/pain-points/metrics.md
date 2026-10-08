# Metrics lifecycle

## Invariant

Execution outcomes follow successful database commits, not acceptance,
event emission or replay. PostgreSQL compare-and-set and turn transactions are
the deduplication authority. Active execution gauges describe shared database
state; other work/load gauges describe the current process. See the
[metric reference](../metrics.md) for names, labels, scrape access and queries.

## Why it is fragile

Option handoffs, client-tool results, cancellation and retry/replay can all
observe the same execution. Counting events or saved cumulative usage duplicates
outcomes/tokens. A client disconnect does not terminate durable thread work.
Dropping a durable bus wake-up is recoverable by replay, whereas evicted live
progress and accepted cross-channel commands cannot be replayed.

## Safe-change guidance

- Keep registry/exporter dependencies in `:backend`; agent telemetry hooks have
  no exporter or host dependencies.
- Observe committed transitions in the repository's post-commit callback. Do
  not count terminal repairs or repeated reads. Preserve usage logging in the
  existing execution finalizer.
- Instrument actual provider attempts inside retries and budget admission.
  Streaming usage is a delta within one attempt; saved usage is never a new
  observation.
- Pair pending/connection increments with outermost `finally` cleanup. Keep
  client-result timing inside the existing client-tool lifecycle.
- Keep elapsed clocks monotonic, labels bounded and histogram buckets suitable
  for configured timeouts. Never tag IDs, raw routes, arbitrary model/tool
  names, arguments, results or exception messages.
- Keep scraping outside trusted user provisioning and restrict `/metrics` at
  deployment ingress. Health/scrape traffic stays excluded from HTTP statistics.

## Verification

Run `./gradlew :backend:test :agent:test souzGateFast`. Focus on
`BackendMetricsE2eTest`, execution/option/public WebSocket E2E suites,
`BackendExecutionLlmChatApiTest`, `ExecutionMetricsTest`, `AgentEventBusTest`
and `AgentToolExecutorTest`. Verify terminal idempotency, retry and stream
accounting, wait exclusions, gauge cleanup, route cardinality and live drops.
