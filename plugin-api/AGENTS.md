# Plugin API

Public JVM SDK for trusted, operator-installed Souz plugins. Keep host, agent, Ktor,
filesystem, and database implementation types outside this module. Jackson JSON
values and Kotlin runtime types are the shared binary boundary.

## Pain points

- [Plugin contract](docs/plugin-contract.md) — packaging, invocation ownership, storage, and HTTP.

## Verification

Run `./gradlew :plugin-api:check :plugin-runtime:test`.
