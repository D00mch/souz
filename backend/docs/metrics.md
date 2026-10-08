# Prometheus metrics

`GET /metrics` returns Prometheus text exposition (`text/plain; version=0.0.4`). It is a trusted-network system route without end-user or proxy headers. Block public ingress to this path; permit only the monitoring network. Scraping reads execution counts from PostgreSQL and does not start agent work or modify conversations.

```sh
curl http://127.0.0.1:8080/metrics
```

Example Prometheus configuration for a scraper on the backend network:

```yaml
scrape_configs:
  - job_name: souz
    scrape_interval: 15s
    static_configs:
      - targets: [backend:8080]
```

## Application meters

| Name | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `souz_executions_total` | Counter | `outcome` | Committed active-to-terminal transitions: `completed`, `failed`, `cancelled`. |
| `souz_execution_duration_seconds` | Histogram | — | Running/cancelling processing time, excluding queued time and the union of explicit waits. |
| `souz_executions_active` | Gauge | `state` | PostgreSQL execution counts: `queued`, `running`, `waiting_option`, `cancelling`. |
| `souz_execution_wait_duration_seconds` | Histogram | `reason` | Individual `user_option` and `client_tool` waits; overlapping client calls each have a sample. |
| `souz_llm_requests_total` | Counter | `provider`, `model`, `outcome` | Actual chat, streaming and embedding attempts, including retries and nested calls. |
| `souz_llm_request_duration_seconds` | Histogram | `provider`, `model`, `outcome` | Attempt latency; streaming includes collection. |
| `souz_llm_tokens_total` | Counter | `provider`, `model`, `direction` | Reported `input`/`output` chat tokens; cached input is included. |
| `souz_tool_calls_total` | Counter | `category`, `outcome` | Compiled tools and client transports, including Skill helper calls. |
| `souz_tool_duration_seconds` | Histogram | `category`, `outcome` | Tool latency, including client-result waits. |
| `souz_pending_tool_calls` | Gauge | — | Outstanding compiled tools and client transports on this instance. |
| `souz_ws_connections_active` | Gauge | — | Open public Client-Souz sockets on this instance. |
| `souz_ws_events_dropped_total` | Counter | `reason` | Lost public live events per subscriber: `queue_full`, `undelivered`, `disconnect`, `send_failure`, `overtaken`. |

Call outcomes are `success`, `error`, `timeout`, `cancelled`. `timeout` covers returned HTTP 408/504 responses and propagated timeout exceptions; provider adapters that convert transport failures to status `-1` are counted as `error`. Providers and tool categories come from their enums; unknown tool categories and custom model IDs become `other`. Models use the provider's known chat/embedding aliases. No user, chat, execution or tool-call IDs, arguments, raw URLs or exception messages become labels.

Execution outcomes count only successful database commits. Request retries, repeated terminal writes and event replay do not add outcomes. Counters are process-local and cannot guarantee observations across a crash between a database commit and its metrics callback. Active execution gauges represent shared database state: use `max`, not `sum`, across replicas using the same database. Other gauges and counters represent instance-owned work.

Elapsed time uses monotonic clocks. Processing and wait samples are process-local; restart or continuation on another replica cannot reconstruct earlier segments. A terminal commit records the observed processing duration. Client waiting covers the actual wait for a result; command preparation/persistence remains processing. Overlapping client waits exclude their union from processing, and option timing remains independent of client waits.

Streaming usage adds only positive deltas above each attempt's high-water mark, retaining it through missing, repeated or decreasing snapshots. Missing usage adds no measured tokens. Anthropic cache-read tokens are added to its uncached/cache-creation input; OpenAI-compatible cached tokens are already part of prompt tokens. Cumulative execution usage is not added to these counters. Embedding APIs do not report usage through the shared response contract.

Live-event losses cover command queue rejection, queue eviction, abandoned queues/receives, disconnects, failed sends and progress overtaken by durable replay. Each full command queue counts a `queue_full` loss even if another subscriber accepts the command. Durable replay signals and live-only bot observers are excluded. A scrape may contain zero outcome/wait/drop series before use; provider/tool series register on first use.

## Host meters

Ktor exports `ktor_http_server_requests_seconds` (histogram/count/sum) with `method`, `route`, `status`, `address`, `throwable`, and `ktor_http_server_requests_active` (gauge). Routes use templates; unknown routes share one series, `address="backend"`, and exceptions use `none`/`error`. `/health` and `/metrics` are excluded.

JVM/process binders export memory (`jvm_memory_*`), GC (`jvm_gc_*`), threads (`jvm_threads_*`), loaded classes (`jvm_classes_*`), CPU (`process_cpu_usage`, `system_cpu_*`) and uptime (`process_uptime_seconds`). Hikari exports pool gauges `hikaricp_connections{,_active,_idle,_max,_min,_pending}`, acquisition/creation/usage timers `hikaricp_connections_{acquire,creation,usage}_seconds`, and `hikaricp_connections_timeout_total`, labelled by the fixed pool name.

Application, HTTP and pool timers export `_bucket`, `_count`, `_sum`, `_max`; histogram boundaries are 0.01, 0.05, 0.1, 0.5, 1, 5, 10, 30, 60, 120, 300, 600, 900 seconds plus infinity. Sum buckets/counts across replicas to calculate percentiles; no per-instance quantiles are needed.

## Queries

```promql
# Execution throughput and failure ratio
sum(rate(souz_executions_total[5m]))
sum(rate(souz_executions_total{outcome="failed"}[5m])) / sum(rate(souz_executions_total[5m]))

# Processing p95 and token rate
histogram_quantile(0.95, sum by (le) (rate(souz_execution_duration_seconds_bucket[5m])))
sum by (provider, direction) (rate(souz_llm_tokens_total[5m]))

# Shared execution load (one database)
max by (state) (souz_executions_active)
```
