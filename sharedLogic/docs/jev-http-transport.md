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

The hosted evaluation is pending. The implementation environment denies HTTPS CONNECT to
`api.typesafe.ai` with HTTP 403 before TLS negotiation. No authenticated request reaches Jev, so
endpoint HTTP/2 support, server idle retention, and hosted median/p95 latency remain unverified.
There are **zero hosted latency samples** and no measured basis for adopting HTTP/2 as the default.
The playground's timing boundary and negotiated protocol are also unconfirmed. Keep CIO as the
default until a permitted environment produces the comparison below.

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
