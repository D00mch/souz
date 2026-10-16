# Example plugins

This aggregate project contains independently packaged plugins. Plugins compile
against `:plugin-api`, never backend internals. Read the child module policy before editing.

## Pain points

See the [SDK contract](../plugin-api/docs/plugin-contract.md).

## Verification

Run `./gradlew :plugins:saved-links:test :plugins:saved-links:jar :plugins:gmail:test :plugins:gmail:jar`.
