# Trusted plugin SDK

Plugins are operator-reviewed JVM code, with the same process privileges as Souz.
The invocation APIs provide ownership boundaries, not a security sandbox. Plugin
instances are shared across users: do not retain call contexts, credentials, user
data, threads, or callbacks. Background schedulers and arbitrary HTTP routes are
outside this SDK. Use cooperative cancellation and structured coroutine work.

## Building a plugin

Publish the SDK locally with `./gradlew :plugin-api:publishToMavenLocal`, then use
`compileOnly("ru.souz:plugin-api:1.0.0")` in a Java 21 Kotlin/JVM project. SDK 1 is
tested with Kotlin 2.4.10, Jackson 2.21.3, and coroutines 1.11.0. The published
Gradle metadata/POM records the SDK dependencies and coroutine version constraint.
Use these versions; do not embed SDK, Kotlin, Jackson, or coroutines classes in a
plugin JAR. Bundle private dependencies inside the JAR and relocate packages that
conflict with host libraries. Manifest `Class-Path` dependencies are rejected so
the cached artifact fully determines the implementation. Plugins depend only on
the SDK, not backend or agent modules.

Implement `SouzPlugin` with a public no-argument constructor and include these
main manifest attributes:

```text
Souz-Plugin-Id: saved_links
Souz-Plugin-Version: 1.0.0
Souz-Plugin-Api: 1
Souz-Plugin-Class: ru.souz.plugins.links.SavedLinksPlugin
```

IDs match `[a-z][a-z0-9_]{0,31}`. Local tool names use lowercase letters, digits,
and underscores; the host exposes `<pluginId>__<toolName>` (at most 64 characters).
Tool definitions carry a description, an existing Souz category name, and an
object input schema. Supported schema keywords are root `type`, `properties`,
`required`; properties support `type`, `description`, string `enum`, `items`, and
nested `properties`. Other schema keywords are rejected rather than dropped.
Required nested properties, references, unions, and arbitrary JSON Schema are
outside v1. Tool arguments are checked against this subset before invocation.

`tools()` and `start()` run before activation; `stop()` runs after retirement and
the last execution lease. Lifecycle methods must complete cooperatively. A faulty
JVM plugin can require a process restart; closing a loader does not force class
unloading. A failed candidate leaves its previous generation active.

## Invocation services

`PluginCallContext.data` is bound to the execution's authenticated user and the
installed plugin ID. It provides `get`, atomic last-write-wins `put`, `delete`,
and lexically ordered keyset `list`. Keys are nonempty UTF-8 strings up to 512
bytes; JSON records are at most 1 MiB. Listing defaults to 20 records, permits
1–100, and returns a cursor. There are no multi-record transactions or SQL APIs.
State is shared across the user's conversations and plugin generations. Maintain
compatible JSON formats while old and new generations overlap. Disabling or
uninstalling preserves data; deleting the user cascades its records.

`PluginCallContext.http` accepts public HTTP(S) URLs, repeated headers and bounded
byte bodies, and returns status, repeated response headers and bytes. The host
validates literals and connection-time DNS addresses, rejects private/reserved
destinations, disables redirects/retries/shared cookies, and imposes a 30-second
deadline and 1 MiB request/response limits. HTTP error statuses are ordinary
responses. Authentication gateways and file downloads are outside v1.

Return Jackson JSON values. Throw `PluginException` for safe, structured errors;
propagate coroutine cancellation. Log operational messages only, never arguments,
credentials, HTTP bodies, or stored values.

## Saved links example

Build `./gradlew :plugins:saved-links:jar`. The JAR under
`plugins/saved-links/build/libs/` has no backend dependency and is not on the
backend runtime classpath. It provides `saved_links__save`, `saved_links__list`,
and `saved_links__delete`. Saving normalizes a URL, fetches its HTML title, and
upserts a SHA-256-keyed record. Fetch failures retain the URL with `fetchStatus`;
cancellation does not save a record. The example uses the JDK HTML parser from
`java.desktop`, available in the backend's Java 21 runtime.

See [backend deployment](../../backend/docs/plugins.md) and
[generation lifecycle](../../plugin-runtime/docs/generation-lifecycle.md).
