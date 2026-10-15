# Generation lifecycle

The configured installation directory is trusted operator input. Publish a JAR
by writing a `.part` file and atomically renaming it on the same filesystem.
The runtime scans at startup and every five seconds, copying complete artifacts
to an immutable SHA-256-named cache before opening a parent-first classloader.
There is no runtime Kotlin compilation or plugin-to-plugin dependency resolution.

Each active plugin ID has one generation. Manifest, SDK, tool schema, namespace,
category, and collision checks precede `start()` and publication. Duplicate IDs
and invalid replacements retain the current generation. A malformed artifact
whose ID cannot be read suppresses removals for that scan. A failed directory
read leaves the registry intact. Removing a valid JAR retires it for new work.

The registry mutex serializes acquisition, reconciliation, and lifecycle calls;
tool invocations execute outside it. A generation lease covers the complete
execution, including subagents and mid-run input. Retired instances stop and their
classloaders close only after leases drain. Release is idempotent and
non-cancellable. Hosts must also release leases when runtime creation fails.

Backend execution metadata records ID, version and digest before acceptance.
Saved option waits acquire those artifacts when resumed, including after a
restart; unavailable artifacts fail explicitly. Legacy executions have no plugin
bindings. Cached artifacts are retained without automatic pruning so a durable
wait does not silently change implementations. Preserve caches on every replica
that may resume an execution. Reconciliation is independent per replica.

Shutdown stops and joins application work, then stops plugins and closes their
loaders before HTTP/storage resources. Plugin-owned unmanaged work is unsupported.
Cache cleanup is an operator task performed only after confirming that no active
or waiting execution refers to the artifacts.
