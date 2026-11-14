# Trusted JVM plugin SDK

Operators install reviewed Java 21 code that runs with backend process privileges.
Classloaders isolate dependencies. Plugins share process resources and can exhaust them.
The host owns resources and invokes stateless handlers.
Keep constructors and metadata side-effect-free. Do not retain invocation contexts, create
threads or launch detached work. Await structured coroutines and propagate cancellation.

## Build and package

Run from the repository root with Java 21.

```bash
./gradlew :plugin-api:publishToMavenLocal
./gradlew -p plugin-api/examples/hello jar
```

Use the [hello build](../examples/hello/build.gradle.kts) for the compile-only SDK dependency,
Java toolchain and required manifest attributes. Implement one public no-argument
[`SouzPlugin` provider](../examples/hello/src/main/kotlin/example/HelloPlugin.kt) and register it
in [`META-INF/services/ru.souz.plugins.api.SouzPlugin`](../examples/hello/src/main/resources/META-INF/services/ru.souz.plugins.api.SouzPlugin).
The [SDK signatures](../src/main/kotlin/ru/souz/plugins/api/Plugin.kt) define the ABI fingerprint
and stateless suspending handlers with JSON object input schemas, JSON arguments and JSON results.
The host exposes names as `plugin.<pluginId>.<toolId>` in the declared domain category.
Use the [operator commands](../../backend/docs/jvm-plugins.md#configuration-and-administration) to install and verify rollout.

Keep the SDK, Kotlin and coroutines compile-only. Bundle private dependencies and their resources
and service providers in a flat JAR. External manifest `Class-Path` entries are unsupported.
Private classes and resources have no backend classpath fallback. The plugin context classloader
follows suspension. Keep reflection and serializer caches private to each loaded version.
Avoid storing plugin objects in shared-worker thread locals. For Jackson, use a mapper with
`JsonRecyclerPools.nonRecyclingPool()` and serialize nodes through that mapper. `JsonNode.toString()`
and `toPrettyString()` use the default recycler, which can retain retired classloaders.
[Plugin classloader](../../backend/src/main/kotlin/ru/souz/backend/plugins/PluginClassLoader.kt)

## Invocation services

Use the host-supplied owner and invocation metadata. Arguments cannot select the storage owner
or OAuth user. [Service implementation and limits](../../backend/src/main/kotlin/ru/souz/backend/plugins/PluginServices.kt)

- `state` stores bounded JSON shared across conversations and versions for one user/plugin. Keep
  input and state formats compatible across coexisting versions. Publish a new capability ID
  for breaking behavior. Selecting an earlier version leaves stored state intact.
- `http` supports HTTP(S) without inherited credentials, redirects, shared cookies or automatic
  retries. Validate destinations. `oauth` uses host-owned tokens and enforces provider scopes
  and HTTPS host restrictions. Handle `PluginReconnectRequired` for consent.
- `files` uses owner-scoped stored-file IDs and existing size and expiry rules without resolving
  a sandbox. A false result, exception or cancellation from `channels.send` can mean uncertain
  delivery. Persist business intent before sending and reconcile uncertain effects before retrying.
- `log` and `PluginException` messages must be safe to expose. Omit credentials, arguments, HTTP
  bodies and stored user data. Reserve `TECHNICAL` for failures such as provider outages.

On cancellation or lease failure, stop work and propagate the failure. Use durable reservations
for external effects that could otherwise repeat after an uncertain outcome.
[Versions and pins](../../backend/docs/jvm-plugins.md#versions-and-durable-pins) ·
[Invocation and recovery](../../backend/docs/jvm-plugins.md#invocation-and-recovery)
