# Backend Prometheus metrics

`GET /metrics` returns Prometheus text exposition (`text/plain; version=0.0.4;
charset=utf-8`). The registry, JVM binders, HTTP plugin and pool instrumentation
belong to `:backend` and close with application resources. Existing structured
execution usage logs remain available.

## Scraping and access

The scraper uses the backend's trusted network directly, like the health probe.
`/metrics` is outside `/v1` and requires neither `X-User-Id`,
`X-Souz-Proxy-Auth`, nor an end-user session. Restrict the listener/ingress to the
deployment network and allow the monitoring service to reach this route. An
Internet-facing reverse proxy must block `/metrics`; do not put an end-user
login in front of the scrape target. Metrics contain no credentials or user
content. This route does not authenticate an arbitrary Internet caller.

```sh
curl --fail http://souz-backend:8080/metrics
```

```yaml
scrape_configs:
  - job_name: souz-backend
    scrape_interval: 15s
    metrics_path: /metrics
    static_configs:
      - targets: ["souz-backend:8080"]
```

Scraping reads metrics and current active execution counts from PostgreSQL;
it does not start work or change conversations. Scrape failures and database
availability can be monitored with Prometheus `up`. `/health` and `/metrics`,
including requests with query parameters, are excluded from HTTP traffic meters.

## Application families

Post-commit counters are in-process observations, so a crash before recording
or scraping can lose observations. They are monitoring data, not billing receipts.

Histogram families export `_bucket{le}`, `_count`, and `_sum`; durations and
bucket limits are seconds. They also export Micrometer's process-local `_max`
gauge, which is not an aggregate percentile. Counters reset on process restart.

| Prometheus family | Type | Labels | Semantics |
| --- | --- | --- | --- |
| `souz_executions_total` | Counter | `outcome` | `completed`, `failed`, `cancelled`; increments after a successful active-to-terminal database commit, including lease recovery. |
| `souz_execution_duration_seconds` | Histogram | none | Processing across locally observed execution segments, excluding queued time, option waits and the union of client-tool waits. Recorded at the terminal commit. |
| `souz_executions_active` | Gauge | `state` | Current **shared database** counts: `queued`, `running`, `waiting_option`, `cancelling`. Use `max` by state across replicas pointing to the same database, not `sum`. |
| `souz_execution_wait_duration_seconds` | Histogram | `reason` | `user_option` from committed option handoff to committed resume/cancel; `client_tool` for each awaited same-thread or cross-channel call, including timeout/cancellation. |
| `souz_llm_requests_total` | Counter | `provider`, `model`, `outcome` | Each actual chat/embedding attempt. Outcomes: `success`, `error`, `timeout`, `cancelled`. 429 retries count separately; preflight validation, credential resolution and denied budgets are not attempts. |
| `souz_llm_request_duration_seconds` | Histogram | `provider`, `model`, `outcome` | Attempt dispatch through response/stream collection, excluding retry backoff and budget admission. A stream lasts until completion, failure or collector cancellation. |
| `souz_llm_tokens_total` | Counter | `provider`, `model`, `direction` | `input`, `output`; newly reported chat usage, including nested calls and failed/cancelled streams' reported usage. Repeated cumulative stream snapshots contribute only their positive delta. Saved execution usage and option continuations' initial usage are not added. |
| `souz_tool_calls_total` | Counter | `category`, `outcome` | Graph tool invocations (including core helpers and subagents) and client transports invoked through those helpers. Each layer is counted separately; direct/subagent client transport invocations are counted once. Outcomes: `success`, `error`, `timeout`, `cancelled`. Client result status determines its outcome. |
| `souz_tool_duration_seconds` | Histogram | `category`, `outcome` | Invocation duration, including client-result waits. Graph invocations use the existing monotonic tool telemetry clock; client transports use a monotonic clock. |
| `souz_pending_tool_calls` | Gauge | none | **Instance-owned** outstanding graph invocations and client transports. A helper awaiting a client transport contributes two outstanding calls. Released in `finally` on every exit. A same-thread client's disconnect leaves work pending until result, deadline or thread cancellation. |
| `souz_ws_connections_active` | Gauge | none | **Instance-owned** connected WebSocket handlers, including connections undergoing validation. Released on disconnect/failure/cancellation. |
| `souz_ws_events_dropped_total` | Counter | `reason` | Per-delivery losses of live events/accepted commands: `queue_overflow`, `disconnect`, `send_failure`, `overtaken` (progress superseded by durable replay). Durable wake-up eviction is not a lost delivery because replay recovers it. Internal filtering, duplicate replay, absent subscribers and rejected command admission do not increment it. |

Elapsed application durations use `System.nanoTime`. Timing samples are
process-local and removed at terminal completion. They do not reconstruct
pre-restart processing or option-wait duration; a continuation on another
replica measures only that replica's observed processing. Durable active counts
and terminal transition deduplication remain database-backed. Expired leases
recovered without a local timing sample increment outcomes without inventing a
processing duration. Timing samples abandoned by another replica's terminal
commit are pruned during scraping.

Token counters use provider-reported usage; missing usage is not estimated from
text or treated as an observed zero. Provider adapters may represent absent
usage with an empty usage object, which contributes nothing. Cached input is a
subset of input, not additional tokens; no separate cached-token family is
exported. Embedding responses have no usage contract and contribute no tokens.
File upload/download, balance and image-generation HTTP operations are not chat
or embedding attempts. Graph tool success means the invocation returns normally;
returned business-error content is not reinterpreted by generic telemetry.
Client transports use their explicit result status instead.

Provider values are lowercase `LlmProvider` enum names. Model values are exact
aliases in `LLMModel` or `EmbeddingsModel` for that provider, or `other` for custom deployments and
dynamic model IDs. Categories are lowercase `ToolCategory` enum names, or
`other`. No raw tool/model names, user/chat/thread/call/request IDs, prompts,
arguments, URLs, or error messages become application labels. Known outcome,
wait, model and category series start at zero; JVM event-dependent families
(for example GC pauses) appear after their first matching event.

## HTTP, JVM and PostgreSQL

| Family | Type / unit | Labels |
| --- | --- | --- |
| `ktor_http_server_requests_seconds` | Histogram / seconds; `_count` is the request count | `method`, `route`, `status`, `address`, `throwable` |
| `ktor_http_server_requests_active` | Gauge / requests | none |
| `jvm_memory_used_bytes`, `jvm_memory_committed_bytes`, `jvm_memory_max_bytes` | Gauge / bytes | `area`, `id` |
| `jvm_gc_pause_seconds` | Histogram / seconds | `action`, `cause`, `gc` |
| `process_cpu_usage`, `system_cpu_usage` | Gauge / ratio | none |
| `system_cpu_count` | Gauge / cores | none |
| `jvm_threads_live_threads`, `jvm_threads_daemon_threads`, `jvm_threads_peak_threads` | Gauge / threads | none |
| `jvm_threads_states_threads` | Gauge / threads | `state` |
| `process_uptime_seconds`, `process_start_time_seconds` | Gauge / seconds | none |
| `hikaricp_connections_active`, `hikaricp_connections_idle`, `hikaricp_connections`, `hikaricp_connections_max`, `hikaricp_connections_min`, `hikaricp_connections_pending` | Gauge / connections or waiting borrowers | `pool` |
| `hikaricp_connections_acquire_seconds` | Histogram / seconds | `pool` |
| `hikaricp_connections_timeout_total` | Counter / timeouts | `pool` |
| `hikaricp_connections_creation_seconds`, `hikaricp_connections_usage_seconds` | Timer (`_count`, `_sum`, `_max`) / seconds | `pool` |

Standard binders also expose classloading, GC allocation/promotion and buffer
pool families. HTTP `route` uses the Ktor route template, with `n/a` for
unregistered paths; arbitrary methods are excluded by Ktor. `address` is the
constant `backend`; `throwable` is `none` or `error`. Request Host headers and
exception details cannot add label values. HTTP acceptance measures HTTP delivery, independently of the
eventual execution outcome. JVM, pool, socket and pending-tool gauges are per
instance and can be aggregated as appropriate; database execution gauges are
the explicit shared-state exception.

Application, HTTP, GC pause and pool acquisition histograms support replica
aggregation, with finite buckets through 900 seconds. Explicit limits include
1, 5, 10, 30, 60, 120, 300, 600 and 900 seconds in addition to Micrometer's
histogram buckets and `+Inf`. Use histogram queries, not process-local
percentile gauges.

## Example queries

Completed executions per second:

```promql
sum(rate(souz_executions_total{outcome="completed"}[5m]))
```

Failure ratio among all terminal executions:

```promql
sum(rate(souz_executions_total{outcome="failed"}[5m]))
/ clamp_min(sum(rate(souz_executions_total[5m])), 1e-9)
```

Processing p95 across replicas:

```promql
histogram_quantile(0.95, sum by (le) (rate(souz_execution_duration_seconds_bucket[5m])))
```

Tokens per second by provider and direction:

```promql
sum by (provider, direction) (rate(souz_llm_tokens_total[5m]))
```

Shared execution load (scope this query to one database/deployment):

```promql
max by (state) (souz_executions_active)
```
