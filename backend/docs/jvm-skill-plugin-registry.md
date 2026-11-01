# JVM Skill plugin registry

The backend distribution contains an operator command for publishing reviewed Java/Kotlin
artifacts to PostgreSQL. It writes registry state without constructing providers, executing
plugin initialization, starting HTTP routes or starting application workers.

## Operator commands

Use the backend distribution's normal database configuration (`POSTGRES_DSN` or
`SOUZ_BACKEND_DB_*`, including schema). The operator process uses the existing datasource
and Flyway initialization; deployment credentials therefore need the normal migration
permissions as well as registry writes. Keep those credentials in the operator/CI channel.

```bash
bin/backend plugins install /reviewed/reference-v1.jar 0
bin/backend plugins status
bin/backend plugins install /reviewed/reference-v2.jar 1
bin/backend plugins select reference v1 2
bin/backend plugins disable reference 3
```

From a source checkout, use
`./gradlew :backend:run --args='plugins status'`.
Status lists desired registry rows; it does not establish that a replica has applied them.
The trailing number is the expected desired-row revision; zero means that no row exists.
Every successful selection or disable increments the revision. Conflicts fail without
overwriting another operator's change. Installing an artifact and selecting it are separate
registry operations: a selection conflict can leave the immutable artifact available for
a later explicit selection.

Artifacts have an immutable logical ID/version and SHA-256 digest. Publishing different
bytes under an existing ID/version fails. Installation validates the manifest, SDK/JVM
requirements, archive bounds and absence of packaged shared ABI classes without executing
plugin code. A valid manifest does not prove that provider initialization or invocation works.

No public/client artifact-upload or activation route exists. Ordinary client credentials
confer no database/operator access. Restrict artifact and desired-state table writes to
the trusted deployment boundary. Anyone able to replace these database contents can
change code that a plugin host executes. SHA-256 detects corruption and identifies bytes;
unsigned artifacts do not provide independent provenance or signature verification.

## Author and storage contracts

The [SDK guide](../../skill-plugin-api/README.md) specifies packaging, shared packages,
Java/Kotlin suspension, library isolation, owner state, network bounds and author-owned
interface/data compatibility. Classloaders provide dependency isolation within a trusted JVM,
not a security sandbox or protection from JVM termination, OOM or native failures.

PostgreSQL contains `skill_plugin_artifacts`, `skill_plugin_desired` and
`skill_plugin_state`. State access binds the logical plugin and trusted owner in host code.
The API accepts only a key, JSON data, data schema version and optional expected revision;
JSON fields such as `ownerId` cannot change the partition. Users and plugins may use equal
keys without sharing records. User deletion cascades to that user's plugin state.

Each value is bounded to 64 KiB and each key to 256 UTF-8 bytes. Concurrent conditional
writes succeed only for the matching host-managed revision. Data schema versions and
backward compatibility belong to plugin authors; selecting older code does not revert data.
There is no aggregate storage quota or generic migration service.

Private JAR working copies use host-generated digest names and atomic publication.
Private resource connections disable the JVM-wide JAR cache so normal loader closure can
release and delete working copies, including on Windows. The archive is authoritative in
PostgreSQL; working files are disposable.

## Replica lifecycle

Each replica independently polls desired rows and publishes a complete validated catalog
after provider construction, initialization and schema validation. Preparation runs separately
from polling, so a slow plugin does not block another plugin's disable. Failed preparation
keeps the previous applied version and cleans up partial initialization. A superseded desired
revision cannot publish its candidate. Failed revisions retry after 30 seconds.

One root execution acquires its locally applied versions before runtime construction.
Discovery, calls, structured children and composite commands share that binding. Later roots
see a successful replacement; existing roots continue on their pinned implementation.
Conversation history holds no loader between turns. Locally observed disable removes discovery
and rejects further dispatch from existing bindings; already admitted calls may finish.
Explicit `enabledTools` selections still restrict ordinary, child and composite invocation.

Retired versions close only after all root bindings and actual invocations exit. Rollback may
reuse a healthy draining version; a version already closing must finish before it can load
again. There are at most two live versions per plugin, including preparation and unfinished
cleanup. A third update waits for capacity. Normal shutdown/unload closes the loader and removes
its working JAR without restarting the backend. Persistent/background plugin tasks are outside
the SDK contract; only structured work within an invocation is supported.

| Limit | Default | Configuration |
| --- | --- | --- |
| Registry poll | 1 second | `SOUZ_PLUGINS_POLL_MILLIS` |
| Preparation deadline | 30 seconds | `SOUZ_PLUGINS_PREPARE_MILLIS` |
| Caller deadline | 60 seconds | `SOUZ_PLUGINS_CALL_MILLIS` |
| Cleanup deadline | 10 seconds | `SOUZ_PLUGINS_CLEANUP_MILLIS` |
| Simultaneous preparation / calls | 2 / 32 per replica | Host defaults |
| Shutdown drain / failed revision retry | 30 / 30 seconds | Host defaults |

The four timing settings must be positive; matching properties are
`souz.plugins.poll_millis`, `souz.plugins.prepare_millis`, `souz.plugins.call_millis`,
`souz.plugins.cleanup_millis`.
SQL statements have a 30-second driver deadline and use the existing bounded datasource pool.
Input/result bodies are bounded to 1 MiB. Archive limits are 64 MiB compressed, 256 MiB expanded,
20,000 entries, and a 64 KiB manifest. There is no per-version thread pool or per-user JVM process.

Deadlines cancel cooperative work; they cannot forcibly terminate arbitrary Java/native code.
A caller timeout returns `plugin_timeout` without retrying, and the work may already have
caused an external effect. Actual-use counts continue until the handler exits. Preparation or
cleanup still running beyond its deadline appears as stalled. Failed cleanup keeps the loader
and its capacity occupied rather than claiming successful unload. Inspect the safe failure code,
disable/select a corrected artifact, and restart the affected replica when defective code cannot
drain. Do not use restart as the normal update/unload mechanism.

## Rollout and diagnostics

The operator's `status` reports desired database state. Per-replica applied revision, loaded
version count, application error and registry availability are available through
[plugin metrics](metrics.md#jvm-skill-plugins). Apply/failure logs include logical ID and digest;
diagnostics contain bounded codes/exception class names, not raw plugin exception payloads.
Keep plugin-authored log messages free of sensitive data.

Replicas may temporarily serve different versions. There is no membership protocol, readiness
barrier or globally atomic activation. A failed replica keeps its last successful version.
Registry outages preserve the last-known configuration, including enablement; a remote disable
cannot take effect there until connectivity returns. Operators must monitor each replica and
intervene on unsuccessful rollout/cleanup. A fresh process reconstructs desired state and
artifacts from PostgreSQL without needing the previous working directory.

Plugin authors own parameter, behavior and stored-data compatibility across coexisting versions.
Preserve old inputs under the same capability ID or publish a new ID for a breaking interface.
The host validates ordinary inputs but does not adapt old arguments or migrate business data.

JVM-only requests do not start a user container, even with Docker sandbox mode selected: file
inventory uses mounted host files and Docker starts on the first sandbox command. File/client
Skills continue through their existing execution paths. Per-replica code/heap costs scale with
loaded plugin versions and concurrent work; owner state scales with database records. Container
costs remain for users who execute sandbox commands. See the [reference evidence](jvm-skill-plugin-reference.md)
for repeatable workloads and the limits of the measurements.
