# Plugins

Plugins are trusted, operator-installed JVM JARs. They coexist with filesystem
Skills and LOCAL/DOCKER sandboxes. See the [SDK](../../plugin-api/docs/plugin-contract.md)
for authoring and host services, and [Gmail](../../plugins/gmail/docs/gmail-workflow.md)
for notification setup and Python-state migration.

## Installation and versions

`SOUZ_PLUGIN_DIRECTORY` / `souz.plugins.directory` names an existing operator-owned
directory; omission disables discovery. `SOUZ_PLUGIN_CACHE_DIRECTORY` /
`souz.plugins.cacheDirectory` defaults to `.cache` beneath it. Keep both outside
user workspaces, with a writable durable cache. Use a separate cache mount when
the installation directory is read-only.

Publish `<id>.jar.part` through an atomic rename to `<id>.jar`. Startup and
five-second scans validate each candidate before activation. Invalid replacements
retain the current version; removing a JAR prevents new executions from using it.
Failures log the installation ID, validation reason, and exception cause chain.
Scans and cold recovery share a loading mutex. Copying, hashing, construction and
classloader cleanup occur outside the registry state mutex, so loaded generations
remain available during a scan. Publication and lease accounting use short state
critical sections. Cold recovery may wait for a scan; shutdown drains the loader
and discards unpublished candidates.

Accepted executions persist enabled plugin ID/version/digest bindings. Tool calls,
subagents, and option continuations retain those exact artifacts. Execution leases
close retired classloaders after running work drains; saved waits reload from the
immutable digest cache. Missing/corrupt artifacts or incompatible SDKs fail resume
explicitly. Preserve the cache on each replica that can resume work. Cache pruning
is an operator action after confirming that no running or waiting execution needs
the artifacts. Application shutdown joins work before closing plugins and services.

## Availability and ownership

Installed plugins default to enabled for existing and new users. Bootstrap includes
`capabilities.plugins` with `id`, `version`, `tools`, and `enabled`. Settings expose
`disabledPlugins: []`; patch IDs to opt out and `[]` to re-enable. Unknown valid IDs
remain stored so opt-outs survive uninstall/reinstall. Changes affect subsequent
executions. Compiled `enabledTools` is independent.

Plugin names use `__`, reserved from new client declarations and discovery.
Plugin tools use existing Skill discovery/invocation and selected subagent catalogs;
they add no HTTP routes or WebSocket operations. Server catalog composition protects
pinned plugins from previously stored client-name collisions.

The `plugin_data` table is keyed by authenticated user, installed plugin ID, and
record key. All mutations of a key take the same PostgreSQL transaction advisory
lock, including initial creation and deletion. Update callbacks perform no IO.
Data survives conversation deletion, disabling, and uninstall. User deletion
cascades. HTTP/OAuth clients, sandbox resolution, and channel ownership remain
host-controlled and independent of compiled-tool preferences.

Tool errors distinguish schema errors (`plugin_invalid_arguments`), safe plugin
errors (such as `gmail_claim_inactive`), and unexpected operation failures. Unexpected
failures return a diagnostic ID matching a log entry with plugin/tool identity,
exception types and stack locations. Arbitrary exception messages and invocation
arguments are excluded because they can contain credentials or private content.

## Verification

Run `./gradlew :backend:test :plugins:gmail:test :plugins:gmail:jar` with Docker.
Tests load real JARs and PostgreSQL; transport tests exercise actual retry,
cancellation, body-limit, and DNS policies. Gmail integration replaces only
external HTTP/LLM boundaries. Live Google consent, Pub/Sub setup, and recipient
delivery require deployment verification.
