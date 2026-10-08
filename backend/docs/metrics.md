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

## Local Docker workload

The Compose `monitoring` profile adds Prometheus with five-second scrapes and a
persistent volume retaining seven days of samples:

```sh
docker compose --profile monitoring up -d --build
```

Open [targets](http://127.0.0.1:9090/targets) and the
[query console](http://127.0.0.1:9090/query). The UI binds to loopback;
`SOUZ_PROMETHEUS_HOST_PORT` overrides port 9090. The backend is scraped through
the Docker network at `backend:8080`.

With a real provider configured, run the bounded workload using Node 22+:

```sh
SOUZ_URL=http://127.0.0.1:8080 SOUZ_MODEL=gpt-5.2 node tools/metrics-workload.mjs
```

This creates four chats for a fresh synthetic user and incurs real provider
usage. It checks a plain reply with an idempotent submit retry, a successful
`user.ask` result, an emulated client timeout, and cancellation during a client
wait. Two scenarios run concurrently; client waits last ten seconds so scrapes
can capture active gauges. Each scenario has a three-minute deadline and no
automatic retry. Chat/thread IDs are printed for inspection; chats remain in
the database. Provider behavior is nondeterministic, so a missing requested
tool call fails the workload rather than claiming tool coverage.

Compare `souz_executions_total`, `souz_llm_requests_total`,
`souz_llm_tokens_total`, `souz_tool_calls_total` and
`souz_execution_wait_duration_seconds_count` before and after. Expected execution
deltas are three completed and one cancelled; the duplicate submit adds no
execution. Client timeouts count as tool timeouts, even when the agent completes
its reply. After the next scrape, pending calls, active executions and connected
WebSockets should return to their pre-workload levels. Use `max_over_time` on
the gauges to inspect their peaks.

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
| `souz_execution_wait_duration_seconds` | Histogram | `reason` | `user_option` from committed option handoff to committed resume/cancel; `client_tool` for each same-thread or cross-channel transport invocation, including validation, dispatch, result waiting and cleanup. |
| `souz_llm_requests_total` | Counter | `provider`, `model`, `outcome` | Each actual chat/embedding attempt. Outcomes: `success`, `error`, `timeout`, `cancelled`. 429 retries count separately; preflight validation, credential resolution and denied budgets are not attempts. |
| `souz_llm_request_duration_seconds` | Histogram | `provider`, `model`, `outcome` | Attempt dispatch through response/stream collection, excluding retry backoff and budget admission. A stream lasts until completion, failure or collector cancellation. |
| `souz_llm_tokens_total` | Counter | `provider`, `model`, `direction` | `input`, `output`; newly reported chat usage, including nested calls and failed/cancelled streams' reported usage. Repeated cumulative stream snapshots contribute only their positive delta. Saved execution usage and option continuations' initial usage are not added. |
| `souz_tool_calls_total` | Counter | `category`, `outcome` | Graph tool invocations (including core helpers and subagents) and client transports invoked through those helpers. Each layer is counted separately; direct/subagent client transport invocations are counted once. Outcomes: `success`, `error`, `timeout`, `cancelled`. Client result status determines its outcome. |
| `souz_tool_duration_seconds` | Histogram | `category`, `outcome` | Invocation duration, including client-result waits. Graph invocations use the existing monotonic tool telemetry clock; client transports use a monotonic clock. |
| `souz_pending_tool_calls` | Gauge | none | **Instance-owned** outstanding graph invocations and client transports. A helper awaiting a client transport contributes two outstanding calls. Released in `finally` on every exit. A same-thread client's disconnect leaves work pending until result, deadline or thread cancellation. |
| `souz_ws_connections_active` | Gauge | none | **Instance-owned** connected WebSocket handlers, including connections undergoing validation. Released on disconnect/failure/cancellation. |
| `souz_ws_events_dropped_total` | Counter | `reason` | Per-delivery losses of live events/accepted commands: `undelivered` (channel eviction, shutdown or cancelled receive), `disconnect` (closed publication or cancelled forwarding), `send_failure`, `overtaken` (progress superseded by durable replay). Durable wake-up eviction is not a lost delivery because replay recovers it. Internal filtering, duplicate replay, absent subscribers and rejected command admission do not increment it. |

Timing uses monotonic clocks and process-local samples. Restart or continuation on
another replica loses earlier timing segments; shared active counts and terminal
deduplication remain database-backed. Scraping prunes samples completed by another
replica. Provider tokens come from reported chat usage, including partial streams;
missing usage contributes nothing, cached input is part of input, and saved usage
is never re-added. Embeddings have no token usage contract; uploads, downloads,
balance and image generation are excluded from LLM attempts.

Labels are bounded: lowercase provider/category enums, exact model aliases from
`LLMModel`/`EmbeddingsModel` for that provider, and `other` for unknown values.
IDs, payloads, URLs and error messages are excluded. Execution outcome, wait and
drop series start at zero; provider and tool series register on first use.
Graph tool success means normal return; client transports use result status.

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
| `hikaricp_connections_creation_seconds`, `hikaricp_connections_usage_seconds` | Histogram / seconds | `pool` |

Standard binders also expose classloading, GC allocation/promotion and buffer
pools. HTTP routes use templates or `n/a`, `address` is `backend`, and `throwable`
is `none` or `error`; Host headers and exception details cannot expand labels.
Ktor excludes unknown HTTP methods. HTTP acceptance is independent of agent outcomes.

All timers use explicit histogram buckets
at 0.01, 0.05, 0.1, 0.5, 1, 5, 10, 30, 60, 120, 300, 600 and 900 seconds, plus
`+Inf`. No additional generated buckets or local percentiles are published.

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
