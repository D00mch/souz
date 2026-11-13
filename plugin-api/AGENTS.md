# Plugin API

`:plugin-api` defines the Java/Kotlin ABI for trusted stateless JVM plugins.
Keep signatures limited to SDK-owned values, JDK/Kotlin types, and suspending methods.

## Pain points

- [SDK contract](docs/plugin-contract.md) — ABI, packaging, isolation, services,
  serialization, and cancellation.

## Verification

```bash
./gradlew :plugin-api:check :plugin-api:publishToMavenLocal :backend:test
./gradlew -p plugin-api/examples/hello jar
```
