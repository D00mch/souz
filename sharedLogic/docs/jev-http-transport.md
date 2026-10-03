# Jev HTTP transport evaluation

Jev's production default is the shared, host-owned CIO client. `JEV_TRANSPORT` opts into a dedicated
host-owned client: `CIO`, `OKHTTP_HTTP1`, or `OKHTTP_HTTP2`. Other providers retain their existing profiles.
OkHttp HTTP/2 uses TLS ALPN with HTTP/1.1 fallback. Each experimental client has its own pool;
the two OkHttp profiles differ only in their protocol list. Automatic connection-failure retries are
disabled so hidden retries do not distort the comparison or repeat Jev POSTs.

`JEV_IDLE_RETENTION_MS` sets the dedicated CIO keep-alive or OkHttp idle-pool ceiling, defaulting to
60 seconds. OkHttp retains at most five idle connections; this serial single-endpoint experiment
needs only one. Server-side closes can shorten retention. Jackson serialization, request-local Bearer
authentication, payload, the two-second request timeout, and coroutine cancellation use the existing
provider/Jev contracts. The host closes the dedicated client with `ProviderHttpClients`.

## Findings and adoption decision

The local TLS fixture verifies HTTP/2 negotiation, HTTP/1.1 fallback, forced HTTP/1.1 against an
HTTP/2-capable server, repeated-request connection reuse, complete-body timing, timeout, cancellation,
continued client usability after cancellation, and host resource shutdown.

Ktor 3.5.1 CIO sends Jev's POST on a dedicated connection and closes that socket at call completion.
Its `EngineTasks.kt` routes methods other than GET/HEAD to `requiresDedicatedConnection`, and
`Endpoint.kt` closes dedicated sockets. A local TLS test confirms different connection IDs for
consecutive Jev POSTs with both the default CIO client and the 60-second CIO profile. Consequently,
matching the configured idle ceiling cannot make the CIO POST path behave like OkHttp's pool.
Any CIO-to-OkHttp improvement includes an engine/connection-reuse effect; compare the two OkHttp
profiles to estimate the incremental HTTP/2 effect. A longer CIO keep-alive alone is not a sufficient
fix for this request path.

For repeated serial Jev calls, prefer **`JEV_TRANSPORT=OKHTTP_HTTP1`** on the measured network path.
It captures the large engine/pooling benefit and has the lowest immediate-repeat p95 in both runs.
HTTP/2 has the fastest immediate-repeat median, but its incremental benefit is small and it does
not win consistently across phases and tail latency. All profiles completed without failures;
these samples establish no failure-rate advantage for either OkHttp profile.

Keep the production default as CIO. CIO has the lowest first-request medians, while the pooled
profiles suit long-lived clients. One network path and one synthetic workload do not establish a
winner for all host lifecycles. HTTP/2 support is confirmed on this path, but the measurements do
not justify adopting HTTP/2 as the production default.

## Hosted measurements — 2026-10-04 MSK

Two complete 20-round experiments ran on the same local macOS 26.7 arm64 workstation with the
Gradle Java 21 toolchain (Azul Zulu 21.0.10+7-LTS, x86_64 installation) and Ktor 3.5.1.
Run 1 covered 2026-10-03 21:05:49–21:15:21 UTC; run 2 covered 21:25:10–21:34:42 UTC.
Both used the existing `JEV_TOKEN`, fixed `JEV_MODEL=jev-latest`, identical state/questions,
20,000 ms configured idle, 60,000 ms dedicated retention, and the unchanged 2,000 ms request timeout.
The authenticated [model catalog](https://api.typesafe.ai/redoc) exposed `jev-latest` and
`jev-preview`, with no versioned model ID; the request model was fixed, but its alias resolution
was not recorded.

All four profiles reached `https://api.typesafe.ai/v1/systemone` and passed answer validation.
The sampled route to the endpoint's IPv4 address used the existing `utun4` tunnel/VPN interface.
No HTTP proxy environment variables, test JVM proxy properties/arguments, or macOS HTTP proxy
configuration were present. The authenticated catalog probe reported `proxy_used=0`.
The tunnel's exit location and underlying forwarding were not measured.

Each run produced 400 full-body latency samples. Median uses the middle-pair average; p95 uses
nearest rank. Times below are milliseconds, rounded to one decimal. Counts and protocols apply
separately to **each** run; every sample succeeded, with zero HTTP errors, timeouts, transport
failures, cancellations, or answer-validation failures.

| Profile | Phase | Successful / total per run | Failures per run | Actual protocol (count per run) | Run 1 median / p95 ms | Run 2 median / p95 ms |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| Standard CIO | First | 20 / 20 | 0 | HTTP/1.1 (20) | 485.5 / 671.4 | 484.4 / 531.4 |
| Standard CIO | Immediate | 60 / 60 | 0 | HTTP/1.1 (60) | 478.2 / 556.7 | 476.4 / 556.2 |
| Standard CIO | Post-idle | 20 / 20 | 0 | HTTP/1.1 (20) | 473.5 / 571.1 | 497.8 / 602.8 |
| Dedicated CIO, 60 s | First | 20 / 20 | 0 | HTTP/1.1 (20) | 474.7 / 524.0 | 483.9 / 643.8 |
| Dedicated CIO, 60 s | Immediate | 60 / 60 | 0 | HTTP/1.1 (60) | 486.3 / 582.2 | 473.5 / 551.0 |
| Dedicated CIO, 60 s | Post-idle | 20 / 20 | 0 | HTTP/1.1 (20) | 472.4 / 583.0 | 479.8 / 525.8 |
| OkHttp HTTP/1.1 | First | 20 / 20 | 0 | HTTP/1.1 (20) | 550.0 / 676.3 | 579.5 / 665.7 |
| OkHttp HTTP/1.1 | Immediate | 60 / 60 | 0 | HTTP/1.1 (60) | 286.3 / 356.9 | 288.0 / 345.0 |
| OkHttp HTTP/1.1 | Post-idle | 20 / 20 | 0 | HTTP/1.1 (20) | 283.8 / 355.5 | 289.9 / 373.6 |
| OkHttp HTTP/2 with fallback | First | 20 / 20 | 0 | HTTP/2.0 (20) | 563.3 / 654.8 | 556.3 / 707.9 |
| OkHttp HTTP/2 with fallback | Immediate | 60 / 60 | 0 | HTTP/2.0 (60) | 283.0 / 381.2 | 278.8 / 363.8 |
| OkHttp HTTP/2 with fallback | Post-idle | 20 / 20 | 0 | HTTP/2.0 (20) | 295.7 / 345.7 | 288.6 / 356.1 |

The HTTP/2 profile negotiated HTTP/2.0 on all 100 requests per run, including every post-idle
request; no hosted HTTP/1.1 fallback occurred. The other profiles each observed HTTP/1.1 on all
100 requests per run. These are response versions reported by Ktor, rather than inferred from
the configured protocol list. Fallback remains verified by the local TLS fixture.

The repeatable gain is **CIO to OkHttp**, including engine and connection-pool differences.
Against standard CIO, OkHttp HTTP/1.1 reduced immediate medians by 40.1% / 39.6% and post-idle
medians by 40.1% / 41.8% (run 1 / run 2). It was faster in every round for both phases in both
runs, comparing each round's three-repeat median or single post-idle sample. Dedicated CIO's
60-second keep-alive produced no consistent improvement over standard CIO, matching the local
evidence that this POST path does not reuse connections.

The incremental **OkHttp HTTP/1.1 to HTTP/2** result is mixed. Immediate medians improved by
1.2% / 3.2%, but immediate p95 worsened by 6.8% / 5.5%. Post-idle median changed from 4.2%
slower in run 1 to 0.4% faster in run 2; post-idle p95 improved by 2.8% / 4.7%.
HTTP/2 won the per-round immediate comparison in only 11 / 12 of 20 rounds and the post-idle
comparison in 7 / 12 of 20 rounds. Its small median advantage does not establish a uniform
protocol benefit. Both OkHttp profiles had slower first-request medians than CIO.

Actual idle durations reflect serial requests to the other profiles:

| Profile | Run 1 idle range, seconds | Run 2 idle range, seconds |
| --- | ---: | ---: |
| Standard CIO | 21.00–25.23 | 21.02–25.96 |
| Dedicated CIO, 60 s | 21.04–25.04 | 21.03–25.14 |
| OkHttp HTTP/1.1 | 21.20–25.69 | 21.26–25.72 |
| OkHttp HTTP/2 with fallback | 21.24–25.56 | 21.18–25.96 |

### Limits

- First means a fresh client/pool, not an uncached process: JVM warm-up, DNS, and TLS session
  caches can affect it. The cold-start penalty and repeat gains need separate treatment.
- Runs use one small fixed classification workload, one account, one tunnel route, and serial
  requests. Multiplexing/concurrency, other regions, long idle periods, and different payload
  sizes are unmeasured; server and network variability remain in the full-body duration.
- The fixed `jev-latest` request alias can move. Resolved model identity and hosted connection
  IDs were not captured; post-idle latency alone does not prove reuse of the same socket.
- Twenty first/post-idle samples per profile give a coarse p95 estimate. Zero failures in 200
  calls per profile across both runs does not establish rare-failure behavior.
- The playground's timing boundary and protocol remain unconfirmed; its displayed latency is
  not a controlled comparator for these full-body measurements.

Both hosted benchmark invocations passed their HTTP and answer-validation assertions. Raw
metadata was independently checked against all reported quantiles, counts, and protocol totals.
No credential, payload, or sensitive exception message is needed for the comparison.
`souzGateFast` and the focused `JevHttpTransportTest` / `ProviderHttpClientsTest` run passed
(12 tests, zero failures or skips); documentation links and `git diff --check` passed.

## Reproducible hosted benchmark

Export `JEV_TOKEN` securely and pin `JEV_MODEL` to a concrete model ID if available. Run from a
network that can reach the hosted endpoint:

```sh
SOUZ_BENCHMARK_JEV=1 JEV_MODEL=jev-latest \
JEV_BENCHMARK_ROUNDS=20 JEV_BENCHMARK_IDLE_MS=20000 JEV_IDLE_RETENTION_MS=60000 \
./gradlew :sharedLogic:jvmTest --tests 'ru.souz.jev.JevTransportBenchmarkTest' --rerun
```

The test uses the same fixed synthetic state, named questions, model, credential, and Jev adapter
for every profile. It compares the current CIO default, dedicated CIO with the selected retention,
OkHttp HTTP/1.1 only, and OkHttp HTTP/2 with fallback. It ignores `JEV_TRANSPORT` so all profiles
are always included. Profile order rotates across rounds to reduce order bias.

Each round creates fresh clients for first-request samples, then takes three immediate repeats per
profile, waits the configured idle interval, and takes one post-idle request per profile. The idle
interval is constrained to 10–30 seconds; each raw sample includes its actual idle duration because
serial requests to the other profiles can add time. At the defaults, each profile has 20 first,
60 immediate, and 20 post-idle samples (400 total hosted calls). The benchmark takes several minutes
and uses the normal hosted account quota.

Duration comes from `JevClient`'s monotonic timing through full body receipt, before response JSON
parsing. Console output and the JUnit report include raw metadata and a summary for each profile/phase:
total/successful counts, median (middle-pair average), p95 (nearest rank), actual protocol counts,
and outcome counts. Failures are retained and excluded from successful latency quantiles; the test
fails if requests or answer validation fail. Tokens, questions, state, response bodies, and exception
messages are not emitted by the benchmark. First-request measurements include pool establishment
and may include JVM warm-up; run multiple complete experiments before drawing conclusions.

When reporting hosted results, include run location/network/proxy, JDK/Ktor versions, concrete model,
idle/retention settings, sample counts and failures per phase, median/p95, and protocol counts. Only
label a row HTTP/2 when its responses actually negotiate HTTP/2. If both OkHttp profiles negotiate
HTTP/1.1, report HTTP/2 unavailable on that network path. Evaluate both immediate and post-idle
changes; retain the default if the hosted benefit is absent or uncertain.
