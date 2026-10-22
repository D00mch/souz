# GitHub plugin

An independently loaded, read-only plugin for public GitHub repositories. Use
only the invocation's public HTTP service and fixed GitHub REST endpoints. No
credentials, shell commands, user state, or invocation contexts are retained.
Production dependencies are limited to `:plugin-api`.

## Pain points

- [GitHub tools](docs/github-tools.md) — Skill mapping, pagination, API limits, and installation.
- [SDK contract](../../plugin-api/docs/plugin-contract.md) — packaging and HTTP limits.

## Verification

Run `./gradlew :plugins:github:test :plugins:github:jar`. Tests load the packaged
JAR through the real registry with plugin classes absent from the host classpath.
