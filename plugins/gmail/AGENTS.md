# Gmail

Read-only Gmail tools, compiled against the shared plugin SDK. All user state is
persisted through invocation-bound storage. Never retain contexts or OAuth tokens.

## Pain points

See [Gmail workflow](docs/gmail-workflow.md) for claims, cursors and migration.

## Verification

Run `./gradlew :plugins:gmail:test :plugins:gmail:jar :backend:test` with Docker.
