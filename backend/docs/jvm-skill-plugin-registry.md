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
