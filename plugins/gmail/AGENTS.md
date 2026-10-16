# Gmail plugin

Trusted Gmail JAR, compiled only against `:plugin-api`. Keep Google OAuth tokens
inside the host gateway. Gmail access is read-only; notification delivery uses
the authenticated user's host channels. Never retain contexts or user state in
the shared plugin instance.

## Pain points

- [Gmail workflow](docs/gmail-workflow.md) — migration, cursor ownership, claims, uncertain delivery, and attachments.

## Verification

Run `./gradlew :plugins:gmail:test :plugins:gmail:jar :backend:test` with Docker available.
