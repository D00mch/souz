# Plugin API

`:plugin-api` defines the independent Java/Kotlin ABI for trusted stateless JVM
plugins. It has no production project dependencies. SDK signatures contain only
SDK-owned values, JDK/Kotlin types and suspending methods; do not expose host DTOs,
Jackson, persistence, or backend implementation types.

Invocation services are owner-bound and must not escape the handler. Keep user
state in the host's state service and await structured work before returning.

## Pain points

- [SDK contract](docs/plugin-contract.md) — ABI, packaging, isolation, services,
  serialization, and cancellation.

## Verification

```bash
./gradlew :plugin-api:check :plugin-api:publishToMavenLocal :plugins:gmail:test :plugins:gmail:jar
./gradlew -p plugin-api/examples/hello jar
```
