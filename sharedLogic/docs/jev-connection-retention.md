# Jev connection retention

## Timing boundary

`JevClient.durationMs` uses a monotonic clock, starting before `client.post` and ending after
`response.bodyAsText()`. It includes serialization, connection establishment, HTTP exchange, and
the complete response body; response JSON parsing and classifier selection happen afterward.
The TypeSafe playground's reported approximately 300 ms boundary has not been verified. It cannot
be compared directly with curl's full-request time or Souz's duration without that evidence.

## Transport findings

The application reuses `HttpClient` instances. TCP connection reuse is a separate property.
For the pinned Ktor CIO 3.5.1 engine:

- `EndpointConfig.keepAliveTime` defaults to 5,000 ms.
- `HttpClientEngineConfig.pipelining` defaults to false, which selects dedicated connections.
- `HttpRequestData.requiresDedicatedConnection()` selects dedicated connections for every method
  except GET and HEAD, even when pipelining is enabled.
- `Endpoint.makeDedicatedRequest()` opens a connection and closes its socket when the call completes.
- `keepAliveTime` is used by `ConnectionPipeline`, which Jev's POST never reaches. Pipelined GET
  endpoints also expire after `2 * connectTimeout` (10 seconds by default), independently of this setting.

These facts are available in the [CIO sources](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-cio-jvm/3.5.1/ktor-client-cio-jvm-3.5.1-sources.jar)
and [engine configuration sources](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-core-jvm/3.5.1/ktor-client-core-jvm-3.5.1-sources.jar).
The loopback regression test counts distinct remote TCP socket addresses: three Jev POST requests
make three connections for each timeout profile. A JDK HTTP client control sends two GETs
over one connection to the same server. CIO with pipelining enabled sends two Jev POSTs over two new connections. The server
therefore supports persistence, and the POST behavior is client-side.

## Configuration and ownership

`JEV_KEEP_ALIVE_TIME_MS` accepts a positive integer in milliseconds, defaulting to 5,000.
Use `60000` to trial the requested setting. Blank values select the default; malformed, zero,
negative, and overflowing values fail before the Jev transport is created.

Runtime DI resolves `ProviderHttpClients.jev` lazily. This profile uses the same plugins and TLS
defaults as the standard CIO client, with only its idle setting configurable. It does not change
Anthropic, Codex/OAuth, Qwen, AI Tunnel, OpenAI, voice, or Giga transport settings.
Jev authentication, payload, model, serialization, timeout, and cancellation remain request-local
or adapter-owned as before. `JevClient` never owns or closes the transport.

The desktop and backend stop in-flight work before closing `ProviderHttpClients`. Closure includes
Jev if initialized, deduplicates shared injected instances, preserves close failures, and is idempotent.
It does not instantiate an unused Jev client. The two-client injection constructor shares its
standard client with Jev by default; tests or hosts can explicitly inject a third client.

## Measurements

The opt-in benchmark compares explicit 5,000 and 60,000 ms profiles using the same model, state,
and questions. Each trial creates a fresh client, measures the first request, an immediate repeat,
a request after 10 seconds without requests, and a request after another 30 seconds without requests.
Profile order alternates by trial. It captures `JevClient`'s actual duration log, excluding response JSON parsing.
Median uses the middle value or average of the middle pair; p95 uses the nearest-rank method.
Twenty samples per scenario take about 27 minutes. Small samples are useful for transport confirmation,
but their p95 estimates are unstable.

The local fixture returns a synthetic Noul response and delays the final body byte by 30 ms.
Its timings include that delay and contain no hosted inference, network RTT, or TLS cost.
Local latency results cannot establish hosted latency benefit or hosted server retention.

Java 21 / Linux loopback run with connection-only `strace`, three samples per profile/scenario:

| Scenario | 5,000 ms median / p95 (ms) | 60,000 ms median / p95 (ms) | n per profile |
| --- | --- | --- | --- |
| First request | 51 / 579 | 45 / 51 | 3 |
| Immediate repeat | 45 / 49 | 42 / 43 | 3 |
| After 10 s idle | 48 / 48 | 42 / 42 | 3 |
| After 30 s idle | 45 / 46 | 45 / 47 | 3 |

All 24 POSTs used distinct server-observed TCP socket addresses (12 per profile).
The syscall trace independently records 24 connection attempts to the fixture's port.
Both profiles reconnect for first, immediate, and idle requests. The first process request has
a 579 ms warmup outlier; at n=3 the nearest-rank p95 is the maximum. Tracing overhead and process
warmup make these small latency differences unsuitable as performance evidence. The verified
finding is the absence of POST connection reuse under either idle setting.

Hosted measurement is blocked in the configured cloud environment: HTTPS CONNECT to
`api.typesafe.ai:443` returns proxy HTTP 403 before any Jev request reaches the server.
The observed server connection-retention policy, hosted median/p95, and playground timing boundary
remain unverified. No hosted latency improvement is claimed.

## Reproduce

Run fast connection and lifecycle tests:

```sh
./gradlew :sharedLogic:jvmTest --tests 'ru.souz.jev.JevHttpTransportTest' \
  --tests 'ru.souz.llms.http.ProviderHttpClientsTest'
```

Run the local comparison without credentials:

```sh
SOUZ_TEST_JEV_LATENCY=local JEV_LATENCY_SAMPLES=20 \
  ./gradlew :sharedLogic:jvmTest --tests 'ru.souz.jev.JevLatencyBenchmarkTest' --rerun
```

For hosted measurements, export `JEV_TOKEN` securely and pin `JEV_MODEL` to the same hosted model
for both profiles. Allow `api.typesafe.ai` in the environment's network policy. Keep any configured
proxy and CA trust; when the JVM needs explicit proxy properties, propagate the environment's
non-secret proxy host/port using `JAVA_TOOL_OPTIONS`, without changing the production transport.

On Linux, record connection system calls without recording headers or bytes:

```sh
SOUZ_TEST_JEV_LATENCY=hosted JEV_LATENCY_SAMPLES=20 \
  strace -f -ttt -e trace=connect -o /tmp/jev-connect.log \
  ./gradlew --no-daemon :sharedLogic:jvmTest \
  --tests 'ru.souz.jev.JevLatencyBenchmarkTest' --rerun
```

Read `JEV_SAMPLE`, `JEV_SUMMARY`, and local `JEV_CONNECTIONS` lines in
`build/test-results/jvmTest/TEST-ru.souz.jev.JevLatencyBenchmarkTest.xml` under `sharedLogic`.
Correlate each sample's `startedAtMs` with the target or proxy `connect` calls, excluding Gradle,
DNS, and unrelated connections. A new TCP connection per POST demonstrates lack of reuse;
absence of a new connection must be verified against an existing socket, rather than inferred from latency.
The live benchmark labels reuse as `external-trace` until those diagnostics are inspected.
Do not enable header/body logging or trace send/read/write calls: they can expose credentials and payloads.

## Recommendation

Keep the 5,000 ms default. A 60,000 ms idle setting cannot improve Jev connection reuse with CIO
3.5.1's dedicated POST path. If connection setup is material in a hosted comparison, evaluate a
Jev-only transport that pools POST connections as a separate change, preserving the API contract,
2-second timeout, cancellation, TLS trust, request-local authentication, and host-owned shutdown.
Server retention requires a pooling client measurement; the present transport closes first.
