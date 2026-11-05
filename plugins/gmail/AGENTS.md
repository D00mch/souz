# Gmail

Read-only Gmail tools, compiled against the independent plugin SDK. All user state is
persisted through invocation-bound storage. Never retain contexts or OAuth tokens.
The host serializes complete user/plugin invocations; state reads and writes do
not need transaction callbacks. Persist delivery reservations before channel IO.

## Pain points

See [Gmail workflow](docs/gmail-workflow.md) for claims, cursors and migration.

## Verification

Run `./gradlew :plugins:gmail:test :plugins:gmail:jar :backend:test` with Docker.
