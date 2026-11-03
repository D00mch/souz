# Trusted JVM plugin SDK

Plugins are reviewed, operator-installed Java 21 code running inside the backend.
Classloaders isolate dependencies, not privileges, CPU, memory or native code.
The SDK supplies stateless handlers; resources belong to the host. Constructors
and tool metadata must be side-effect-free. Do not retain invocation contexts,
create threads, or launch detached work. Await structured coroutines before
returning and propagate cancellation.

## Build and package

Publish the SDK locally with `./gradlew :plugin-api:publishToMavenLocal` and compile
against `compileOnly("ru.souz:plugin-api:2.0.0")`. The
[standalone example](../examples/hello/build.gradle.kts) builds using
`./gradlew -p plugin-api/examples/hello jar`.

Implement `ru.souz.plugins.api.SouzPlugin` with one public no-argument provider.
Register its class in `META-INF/services/ru.souz.plugins.api.SouzPlugin`. Each
`PluginTool` contains a local name, description, JSON object input schema, domain
category such as `MAIL`, and a suspending `(String, PluginContext) -> String`
handler. Both arguments and results are JSON strings. The host validates schemas
and arguments and exposes names as `plugin.<pluginId>.<toolId>`.

The JAR manifest requires:

```text
Souz-Plugin-Id: hello
Souz-Plugin-Version: 1.0.0
Souz-Plugin-Api: 2;kotlin=2.4.10;coroutines=1.11.0
Souz-Plugin-Jvm: 21
```

This ABI rejects artifacts compiled against the earlier experimental contracts.
Shared ABI classes are compile-only: `ru.souz.plugins.api.*`, `kotlin.*` and
`kotlinx.coroutines.*`. Bundle all other dependencies, including any JSON library,
and their resources/SPI in a flat JAR. External manifest `Class-Path` dependencies
are unsupported. Private dependencies never fall back to the backend classpath.
The context classloader follows calls across suspension. Library reflection caches
and plugin-specific serializers remain private to the loaded version.

Upload the JAR through the authenticated operator API in Swagger. Upload validates
the archive without constructing its provider. A successful upload acknowledges
registry acceptance; replica application is asynchronous.

## Invocation services

The host supplies trusted identity, request/conversation IDs, locale and timezone.
Arguments never select the storage owner or OAuth user.

- `state` stores JSON strings under user/plugin-scoped keys, shared across
  conversations and plugin versions. It supports get, put, delete and ordered
  keyset listing. Records are bounded to 1 MiB. Complete invocations for the same
  user/plugin are serialized by renewable PostgreSQL leases across replicas;
  individual state operations use short transactions. No author-facing CAS or
  transaction callback is required. Keep formats compatible across active versions.
- `http` accepts HTTP(S) destinations and repeated headers, with bounded bodies and
  deadlines. It has no backend authentication, redirects, retries or shared cookies.
  Trusted authors validate destinations and external API semantics.
- `oauth` supplies SDK-owned transport values and delegates authorization to the
  host. Provider scopes and HTTPS host restrictions apply; tokens never cross the
  boundary. `PluginReconnectRequired` carries a consent URL. Responses are bounded
  while streaming, at most 36 MiB; `maxResponseBytes` can request a smaller bound.
- `files.readWorkspaceText` reads bounded UTF-8 text through the invoking user's
  filesystem. `saveWorkspace` writes under a fresh host-generated path; supply an
  extension, not a destination path. `loadStored` and `saveStored` use owner-scoped
  S3 file IDs and the existing file-storage size and expiry policy, without resolving
  a sandbox.
- `channels.send` uses the user's channel providers. A false result, exception or
  cancellation may mean uncertain delivery. Persist intent before sending.
- `log` suspends to verify invocation ownership, like the other host services. It
  is for bounded operational messages, never tokens, arguments, HTTP bodies
  or stored user data. `PluginException` contains a safe code/message and a failure
  kind; use `TECHNICAL` for provider outages, not ordinary business or authorization
  outcomes.

## Lifetime and failure

One logical request keeps the same ID/version/digest through subagents, saved user
waits and restarts. New requests use the selected default. User tool-setting changes
affect new requests; operator disable and version quarantine block subsequent calls
from existing bindings too. Retirement stops new assignments but permits pinned
continuations. Selecting another version does not retire the previous version.

The default slot wait is 60 seconds; after admission, the invocation deadline is
60 seconds. A queue timeout means the handler did not start. An invocation timeout
can mean an external action occurred without acknowledgement. The host never
automatically replays an invocation. Resources remain accounted for until actual
work exits, even when cancellation is ignored.

On cancellation or lease loss, service use is revoked. A renewable lease cannot
stop arbitrary code or retract an already dispatched external action; expiry can
admit another invocation while uncooperative old code still runs. Durable business
reservations remain necessary to avoid duplicate external effects. Do not swallow
lease failures to continue a workflow.

Authors preserve old inputs and shared data formats across coexisting versions,
or publish a new capability ID for breaking behavior. Rollback does not roll back
stored user state. Old artifacts remain in S3 for exact pinned continuations;
incompatible or missing artifacts fail restoration explicitly.
