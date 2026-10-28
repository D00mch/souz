# Trusted plugin SDK

Plugins are operator-installed Java 21 JVM code with the backend's process privileges.
They are stateless handlers, not a sandbox. Keep user state in `PluginContext.data`;
do not retain contexts, start threads, or create background work. All invocation work
uses structured coroutines and propagates cancellation. The host owns resources.

## Build and install

Publish the contracts locally:

```sh
./gradlew :llms:publishToMavenLocal :skill-oauth-api:publishToMavenLocal :plugin-api:publishToMavenLocal
```

Use `compileOnly("ru.souz:plugin-api:1.0.0")` with the repository's Kotlin, Jackson,
and coroutine versions. The SDK's Gradle metadata supplies those dependencies.
The [standalone example](../examples/hello/build.gradle.kts) builds outside Souz:

```sh
./gradlew -p plugin-api/examples/hello jar
```

Implement `SouzPlugin` with a public no-argument constructor and expose a list of
`PluginTool` values. Each combines `LLMRequest.Function`, a Souz category name,
and a suspending `(ObjectNode, PluginContext) -> JsonNode` handler. Function names
are local; the host exposes `<pluginId>__<name>` (maximum 64 characters).
IDs match `[a-z][a-z0-9_]{0,31}` and local names `[a-z][a-z0-9_]*`.

The JAR manifest contains `Souz-Plugin-Id`, `Souz-Plugin-Version`,
`Souz-Plugin-Api: 1`, and `Souz-Plugin-Class`. Install as `<pluginId>.jar` through
an atomic rename. Do not package SDK, Kotlin, coroutines, Jackson, `:llms`, or
`:skill-oauth-api` classes. Bundle private dependencies; manifest `Class-Path`
dependencies are unsupported. The loader is parent-first, so relocate private
dependencies when their versions conflict with host libraries.

Schemas use the existing `LLMRequest.Parameters`/`Property` types, including nested
required fields and `additionalProperties`. Use an object root. The host shares
schema normalization and argument checking with client tools. Category names
come from `ToolCategory`; Gmail uses `MAIL`.

## Invocation services

- `meta` is host-supplied identity and locale/timezone context. Model arguments
  never choose the storage owner or OAuth user.
- `data` provides get, put, delete, lexically ordered keyset listing, and atomic
  `update`. Return `DataChange(replacement, result)`; null replacement deletes.
  Callbacks are short, pure, non-suspending, and perform no IO. The result returns
  after commit. Keys are nonempty UTF-8 strings up to 512 bytes without NUL;
  records are at most 1 MiB. Listing defaults to 20, permits 1–100, and returns
  `nextCursor`. State is user/plugin-scoped and shared across conversations and
  overlapping plugin versions. Keep stored formats compatible across hot updates.
- `http` accepts public HTTP(S) URLs and repeated headers, with 1 MiB request and
  response limits and a 30-second total deadline. Private/reserved addresses,
  URL credentials, and framing/proxy headers are rejected. The client follows no
  redirects and performs no retries, shared-cookie handling, or proxy routing.
  Status codes remain ordinary responses; it has no host credentials.
- `oauth` reuses `ApiCallRequest`/`ApiCallOutcome` from `:skill-oauth-api`, binding
  the authenticated user. Required scopes come from trusted plugin code. Tokens
  remain in the gateway; per-provider grants and HTTPS host allowlists apply.
  Requests are at most 1 MiB and plugin responses at most 36 MiB, enforced while
  streaming. A request may set `maxResponseBytes` to a smaller limit. Missing host
  OAuth configuration returns a service-unavailable error.
- `files` reads bounded UTF-8 text (1 MiB) from the invoking sandbox and saves up
  to 25 MiB under a fresh host-generated `Documents/souz/plugin-files/<id>/`
  directory. Supply an extension, not a destination path.
- `channels.send` uses that user's channel providers. False, exceptions, or
  cancellation can mean uncertain delivery. Persist intent before sending.
- `log` is for operational messages, never credentials, arguments, bodies, or
  stored user data. `PluginException` carries a safe structured tool error.

See [backend operations](../../backend/docs/plugins.md) and
[Gmail workflow](../../plugins/gmail/docs/gmail-workflow.md).
