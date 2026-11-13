# JVM plugins

Run only reviewed Java 21 plugins. They execute inside the backend process with its privileges.
The [SDK contract](../../plugin-api/docs/plugin-contract.md) owns the ABI, dependency isolation,
packaging and handler requirements. The runtime exposes plugin tools through `enabledTools`.
Unavailable plugin names are reserved and cannot resolve to client or file-backed Skills.

## Configuration and administration

Configure a private artifact bucket separately from expiring user-file storage.

```text
SOUZ_PLUGINS_S3_ENDPOINT=https://s3.example.com
SOUZ_PLUGINS_S3_BUCKET=souz-plugin-artifacts
SOUZ_PLUGINS_S3_ACCESS_KEY=...
SOUZ_PLUGINS_S3_SECRET_KEY=...
SOUZ_PLUGINS_S3_REGION=us-east-1
SOUZ_PLUGINS_S3_PATH_STYLE=true
SOUZ_PLUGINS_ADMIN_TOKEN=...
```

Artifact storage is optional. Administration requires it and the dedicated operator bearer token.
User and proxy credentials grant no operator access. Use Swagger at `/docs` for selection, rollback,
disable, retirement and re-enable. The [admin routes](../src/main/kotlin/ru/souz/backend/http/routes/PluginRoutes.kt)
define the generated OpenAPI schemas, upload limits and errors. With the operator token in the
environment, run these commands from the repository root after [building the hello example](../../plugin-api/docs/plugin-contract.md#build-and-package).

```bash
curl --fail-with-body -H "Authorization: Bearer ${SOUZ_PLUGINS_ADMIN_TOKEN}" \
  -F "jar=@plugin-api/examples/hello/build/libs/souz-plugin-hello.jar" \
  -F "expectedRevision=0" http://localhost:8080/admin/v1/plugins/install
curl --fail-with-body -H "Authorization: Bearer ${SOUZ_PLUGINS_ADMIN_TOKEN}" \
  http://localhost:8080/admin/v1/plugins/hello
```

Use revision zero for a new plugin and the current revision for later mutations. Authenticate
before consuming uploads and validate the bounded archive without running plugin code.
Store the immutable SHA-256-addressed JAR before committing the desired selection. Each ID/version
binds to one digest. Keep all installed artifacts for exact restoration. A `202 Accepted` confirms
the desired state commit. Check the applied state on the serving replica for rollout.
[Administration](../src/main/kotlin/ru/souz/backend/plugins/PluginAdministration.kt)

## Versions and durable pins

Each logical request persists exact `(pluginId, version, digest)` pins with its settings.
Subagents inherit the catalogue. Saved user waits release in-memory references and restore the
same pins on continuation. Missing, corrupt, incompatible or quarantined pins fail explicitly.
Legacy executions without pins receive no plugins.

Replicas prepare all eligible versions asynchronously and keep them loaded even when idle.
A failed rollout can leave an eligible previous default applied. Retirement blocks new assignments
and allows pinned continuations. It unloads code after references and calls drain.
Selecting a retired version makes it eligible again. Disable blocks new admissions for all
versions, and quarantine blocks new admissions to the affected digest, including pinned calls.
Already admitted calls may finish. Selecting another version leaves quarantine in place.
[Runtime](../src/main/kotlin/ru/souz/backend/plugins/BackendPluginRuntime.kt) ·
[`SOUZ_PLUGINS_*` settings and defaults](../src/main/kotlin/ru/souz/backend/plugins/PluginRuntimeConfig.kt)

## Invocation and recovery

Renewable PostgreSQL leases serialize complete invocations by `(userId, pluginId)` across replicas
and versions. Use short transactions and a unique fencing token for each admission. State operations
verify unexpired ownership in the same transaction. Other [owner-bound services](../src/main/kotlin/ru/souz/backend/plugins/PluginServices.kt)
check ownership before dispatch. [Registry, leases and incidents](../src/main/kotlin/ru/souz/backend/plugins/PostgresPluginRepository.kt)

Queue timeout guarantees the handler never started. Invocation timeout can leave the outcome
unknown. Never automatically replay an invocation. Cancellation, timeout and lease loss revoke
services and request cancellation. Account for actual work through cleanup, including outcome
persistence and lease release. Keep renewing while timed-out work runs unless ownership is lost.
An expired lease can admit another call while old code runs. Fencing cannot retract dispatched effects.

Keep deadline and recovery work independent of plugin workers and cancellation callbacks.
Arm recovery before requesting cancellation.
The user/plugin breaker is database-coordinated, and the version breaker is replica-local.
Stuck work or unrecoverable cleanup stops intake and records an idempotent severe incident.
Repeated incidents quarantine the digest. Explicit re-enable clears quarantine and its incident count.
Preserve bounded shutdown with a process watchdog. Keep shared services open under live plugin
code. Deploy with restart-on-failure as in [Compose](../../compose.yaml). Plugin leases protect
invocations only.

## Verification

Run `./gradlew :backend:test :plugin-api:check` with Java 21 and Docker.
[Admin E2E](../src/test/kotlin/ru/souz/backend/plugins/PluginAdministrationE2eTest.kt) exercises uploads
against PostgreSQL and MinIO. [Execution E2E](../src/test/kotlin/ru/souz/backend/e2e/BackendPluginExecutionE2eTest.kt)
covers production catalogue and pin restoration. Verify non-cooperative work and process termination
in [disposable JVMs](../src/test/kotlin/ru/souz/backend/plugins/PluginLifecycleLivenessProcessTest.kt).
