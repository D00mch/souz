# Metrics lifecycle

## Invariant

Execution outcomes follow successful active-to-terminal database commits, not
acceptance, event emission or replay. Active gauges read shared database state;
other work gauges belong to one process. See the [reference](../metrics.md).

## Why it is fragile

Retries, replay and option continuations can observe the same execution repeatedly.
Counting those observations or saved cumulative usage duplicates outcomes/tokens.
Disconnect leaves durable work running; live deliveries can be lost while durable
wake-ups remain recoverable through replay.

## Safe changes

- Keep exporter dependencies in `:backend` and account after repository commits.
- Ignore repeated state observations; keep wait timing across idempotent retries.
- Measure actual provider attempts inside retries and budget admission; streaming
  tokens use positive deltas from an attempt's high-water mark.
- Release pending/socket gauges in `finally`; measure overlapping client waits as
  a union when excluding them from processing time.
- Keep labels bounded and durations monotonic. Restrict `/metrics` at ingress;
  scraping needs no user provisioning. Use `max` for shared execution gauges.

## Verification

Run `./gradlew :backend:test :agent:test souzGateFast`. Existing execution, option
and WebSocket E2E tests check outcomes/waits/gauge cleanup; `BackendMetricsE2eTest`
checks exposition, idempotency and labels. LLM, execution-timing, event-bus and tool
executor tests cover retry/token accounting, wait overlap, drops and cancellation.
