# Plugins

Independently loaded JVM artifacts. Compile against `:plugin-api`; never depend on
the backend or other host contracts, or package shared SDK/Kotlin/coroutine classes.
Bundle private JSON and other libraries. Keep mutable user state in host storage.
Read the plugin's own instructions before editing it.

## Pain points

- [Gmail](gmail/AGENTS.md)
