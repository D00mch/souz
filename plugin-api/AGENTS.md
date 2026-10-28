# Plugin API

Public contracts for trusted stateless JVM plugins. Reuse `:llms` schemas and
`:skill-oauth-api` transport outcomes. Keep host implementation dependencies out.
Invocation services are owner-bound; plugins must not retain them or user data.

## Pain points

See [the SDK contract](docs/plugin-contract.md) before changing these interfaces.

## Verification

Run `./gradlew :plugin-api:check :backend:test :plugins:gmail:test`.
