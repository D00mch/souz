# JVM plugins

Run only reviewed Java 21 plugins with backend process privileges. Expose tools through
`enabledTools` and reserve their names even when unavailable. [SDK contract](../../plugin-api/docs/plugin-contract.md)

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

Administration requires artifact storage and the dedicated operator bearer token. Swagger at
`/docs` documents mutations, limits, and errors. Run from the repository root after
[building the hello example](../../plugin-api/docs/plugin-contract.md#build-and-package).

```bash
curl --fail-with-body -H "Authorization: Bearer ${SOUZ_PLUGINS_ADMIN_TOKEN}" \
  -F "jar=@plugin-api/examples/hello/build/libs/souz-plugin-hello.jar" \
  -F "expectedRevision=0" http://localhost:8080/admin/v1/plugins/install
curl --fail-with-body -H "Authorization: Bearer ${SOUZ_PLUGINS_ADMIN_TOKEN}" \
  http://localhost:8080/admin/v1/plugins/hello
```

Require the current revision for mutations, or zero for a new plugin. Authenticate before consuming
uploads and validate bounded archives without running plugin code. Persist the immutable artifact
before updating selection. Each ID/version binds to one digest. Retain artifacts for exact restoration.
`202 Accepted` confirms the desired state commit. Check the serving replica's applied state for rollout.
[Administration](../src/main/kotlin/ru/souz/backend/plugins/PluginAdministration.kt)

## Versions and durable pins

Each logical request persists exact `(pluginId, version, digest)` pins with its settings.
Subagents inherit the catalogue. Saved user waits release in-memory references and restore the
same pins on continuation. Missing, corrupt, incompatible or quarantined pins fail explicitly.
Legacy executions without pins receive no plugins.

A failed rollout can leave an eligible previous default applied. Retirement blocks new assignments
but preserves pinned continuations. Unload retired code only after references and calls drain.
Reselecting a retired version restores eligibility. Disable blocks every version's new calls.
Quarantine blocks new calls to the digest, including pinned calls, until explicitly cleared.
Already admitted calls may finish.
[Runtime](../src/main/kotlin/ru/souz/backend/plugins/BackendPluginRuntime.kt) ·
[`SOUZ_PLUGINS_*` settings and defaults](../src/main/kotlin/ru/souz/backend/plugins/PluginRuntimeConfig.kt)

## Invocation and recovery

Renewable leases serialize invocations by `(userId, pluginId)` across replicas and versions.
Fence state operations transactionally and other services before dispatch. Release database
connections while running plugin code. [Leases and state](../src/main/kotlin/ru/souz/backend/plugins/PostgresPluginRepository.kt)

Queue timeout prevents handler start and any late claim must be released. Invocation timeout can
leave effects uncertain. Never replay automatically. Cancellation, timeout, and lease loss revoke
services and request cancellation. Keep renewing while timed-out code runs unless ownership is lost.
Lease expiry can admit new work while old code runs, and dispatched effects can still complete.

Keep watchdogs independent of plugin workers and cancellation callbacks. Arm recovery before
cancelling and keep work accounted for and watched through cleanup, even after successful replies.
The user/plugin breaker is database-coordinated and the version breaker is replica-local. Only
technical failures count. After cooldown, one ordinary invocation probes recovery.

Stalled work or unrecoverable cleanup stops intake, records a durable incident once, and triggers
bounded process shutdown. Repeated incidents quarantine the digest. Only explicit re-enable resets
the incident count. Share one shutdown drain and keep host resources open while plugin code runs.
Force failure exit on a stalled drain and deploy with restart-on-failure. [Compose](../../compose.yaml)

## Verification

Run `./gradlew :backend:test :plugin-api:check` with Java 21 and Docker.
[Admin E2E](../src/test/kotlin/ru/souz/backend/plugins/PluginAdministrationE2eTest.kt) exercises uploads
against PostgreSQL and MinIO. [Execution E2E](../src/test/kotlin/ru/souz/backend/e2e/BackendPluginExecutionE2eTest.kt)
covers production catalogue and pin restoration. Verify non-cooperative work and process termination
in [disposable JVMs](../src/test/kotlin/ru/souz/backend/plugins/PluginLifecycleLivenessProcessTest.kt).
