# Plugin runtime

Loads trusted plugin JARs and owns generation lifetimes. Depends only on
`:plugin-api` and JVM runtime libraries. Hosts provide category/collision validation.
Coordinate registry state with coroutine mutexes; propagate cancellation and release
generation leases even when execution setup fails.

## Pain points

- [Generation lifecycle](docs/generation-lifecycle.md) — installation, replacement, and retained artifacts.

## Verification

Run `./gradlew :plugin-runtime:test`.
