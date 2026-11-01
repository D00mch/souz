# Trusted JVM Skill SDK

Plugins compile against this SDK and the host's Java 21 toolchain. Its ABI is
`1;kotlin=2.4.10;coroutines=1.11.0;jackson=2.21.3`; the public `:llms` DTOs are part
of that ABI. Use compile-only dependencies for the SDK, Kotlin, kotlinx-coroutines and
Jackson. Package private dependencies and their SPI resources in one flat JAR.
There is no inter-plugin dependency resolution.

The JAR manifest requires:

```text
Souz-Plugin-Id: reference
Souz-Plugin-Version: v1
Souz-Plugin-Api: 1;kotlin=2.4.10;coroutines=1.11.0;jackson=2.21.3
Souz-Plugin-Jvm: 21
```

Use standard manifest line folding for long values. External `Class-Path` entries
are forbidden. Register exactly one public no-argument `SkillPlugin` provider in
`META-INF/services/ru.souz.plugins.api.SkillPlugin`. Construction must be free of
side effects; acquire resources in `initialize`, and release even partially acquired
resources in `shutdown`.

Work is invocation-scoped: await structured child coroutines before returning. Do not
retain invocation contexts or launch persistent tasks from handlers or lifecycle hooks.
The host may call one version concurrently for multiple users; synchronize shared mutable
resources appropriately and keep user data in the bound state API.

Capabilities use existing `LLMRequest.Function` schemas and the namespace
`plugin.<plugin_id>.<tool_id>` (at most 64 characters). The provider returns existing `LLMRequest.Message`
results; data crossing the boundary must contain only host DTOs and ordinary JSON.
Do not register plugin serializers or deserialize plugin DTOs with the host's
`restJsonMapper`. Keep any plugin-specific reflection/serialization caches and
resources within the loaded version's lifetime.

The classloader delegates platform classes, the root `ru.souz.llms` contract package
(excluding provider subpackages), and these shared package prefixes:

- `ru.souz.plugins.api.`
- `ru.souz.llms.json.`
- `kotlin.`
- `kotlinx.coroutines.`
- `com.fasterxml.jackson.`

Other application classes are invisible through normal delegation. Private classes,
resources and `META-INF/services` are resolved from the plugin JAR; library SPI lookup
uses the plugin's context classloader. These boundaries isolate dependencies, not
privileges, CPU or heap. Only reviewed operator-approved code is appropriate.

Invocation metadata and owner-bound state come from the host. State keys cannot
select another owner or plugin. Each record is bounded to 64 KiB of JSON, with
a positive author-managed data schema version and a host-managed revision.
A null expected revision writes unconditionally, zero inserts only, and a positive
revision performs compare-and-set. A conflict returns null. There is no aggregate
owner storage quota.

The basic HTTP facility has no backend authentication, cookies, redirects or retries.
Requests and responses are bounded to 1 MiB, with a 30-second request deadline.
Plugins remain responsible for validating their network destinations. This helper
does not restrict arbitrary networking by trusted JVM code.

Inputs and result content are bounded to 1 MiB. Cancellation and deadlines are cooperative:
blocking foreign code may continue after the caller receives a timeout. There are no automatic
invocation retries. The host retains the version until actual work and all root executions
using it have exited; a timeout is not permission to close resources still in use.

Authors preserve the meaning of old inputs under a capability ID, or publish a
new ID for a breaking interface. Data changes must support versions coexisting
during updates; selecting older code does not roll back stored data.

Real Java and Kotlin fixture sources live under `backend/src/*PluginTestFixtures`.
Java implementations use Kotlin's continuation ABI for the same suspending handlers;
there is no separate Java execution protocol.
