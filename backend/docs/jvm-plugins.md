# JVM plugins

Plugins are trusted in-process Java 21 code. The independent [SDK](../../plugin-api/docs/plugin-contract.md)
exposes stateless suspending tool handlers with JSON string schemas, arguments and results.
There are no lifecycle callbacks. Only the SDK, Kotlin and coroutines are shared;
private classes, resources and service providers cannot fall back to the backend classpath.
Plugin context classloaders follow coroutine suspension. Local JAR resource handles are not cached globally.

The backend uses one concrete PostgreSQL repository for registry, state, leases and incidents.
`BackendPluginRuntime` owns versions, execution references and actual calls; polling, renewals and
failure deadlines run independently. `PluginServices` creates one revocable owner-bound context per
invocation. Admin routes call the repository and immutable artifact store directly.

## Configuration and administration

Configure a private artifact bucket separately from expiring user-file storage:

```text
SOUZ_PLUGINS_S3_ENDPOINT=https://s3.example.com
SOUZ_PLUGINS_S3_BUCKET=souz-plugin-artifacts
SOUZ_PLUGINS_S3_ACCESS_KEY=...
SOUZ_PLUGINS_S3_SECRET_KEY=...
SOUZ_PLUGINS_S3_REGION=us-east-1
SOUZ_PLUGINS_S3_PATH_STYLE=true
SOUZ_PLUGINS_ADMIN_TOKEN=...
```

Artifact runtime configuration is optional. Administration is disabled without its token.
Use `Authorization: Bearer <SOUZ_PLUGINS_ADMIN_TOKEN>`; user/proxy credentials do not authorize it.
All operations appear in the generated OpenAPI document and Swagger UI, including a binary file picker.

| Method | Path under `/admin/v1/plugins` | Input |
| --- | --- | --- |
| POST | `/install` | Multipart `jar` file and `expectedRevision` |
| GET | empty path | Registry and serving replica status |
| GET | `/{id}` | One plugin's status |
| PUT | `/{id}/selection` | JSON `version`, `expectedRevision` |
| POST | `/{id}/disable` | JSON `expectedRevision` |
| POST | `/{id}/versions/{version}/retire` | JSON `expectedRevision` |
| POST | `/{id}/versions/{version}/reenable` | JSON `expectedRevision` |

Revision zero creates a plugin; subsequent mutations compare the current revision.
Uploads authenticate before body consumption, stream to a temporary file, and allow at most
64 MiB in 120 seconds. Manifest identity, SHA-256, ABI fingerprint, Java requirement,
bounded expanded archive and exactly one local ServiceLoader provider are checked without executing plugin code.
Identity never comes from the uploaded filename.

The host stores immutable `plugins/<sha256>.jar` objects before committing registry selection
in PostgreSQL. The same ID/version cannot refer to different bytes. Mutations return
`202 Accepted` with committed desired version, digest and revision; acceptance does not mean rollout completed.
Status includes authoritative desired state and the serving replica's ID and observed/applied state.
Structured errors distinguish invalid input, unauthorized access, revision/identity conflict,
upload timeout/size and unavailable storage. Failed mutations preserve working registry state.
Retain all installed artifacts; garbage collection is not implemented.

## Reconciliation and versions

Each replica reconciles immediately at startup, then waits 60 seconds plus independently sampled
uniform 0–15 second jitter after each completed poll. Preparation runs separately. Failed preparation
becomes retry-eligible after 30 seconds and retries during a later poll. There is no one-second registry loop.
Lease renewals and invocation admission operate independently of this poll cadence.

Every eligible non-retired version stays loaded, including idle and unselected versions.
Installing a new default does not retire an earlier version. Failed activation retains the previous
applied default only while that version remains eligible. Explicit retirement prevents new assignments;
the selected version's retirement without replacement makes new requests unavailable.
Retired artifacts unload after execution references and actual calls drain, and reload for valid durable pins.
Explicit selection can reactivate a retired version. Quarantine blocks subsequent calls to a digest,
including pinned continuations; selecting another version never clears it. Disable blocks every version's
subsequent calls. Calls already admitted may finish.

Logical requests persist `(pluginId, version, digest)` alongside their settings snapshot before execution.
Subagents inherit the selected catalog. Saved user waits release in-memory references while retaining pins;
continuations restore only those artifacts. Missing, corrupt, incompatible or quarantined pins fail explicitly.
Legacy executions with no pins receive no plugins. Working files are disposable, digest-verified and
atomically published, then removed after unloading. There is no fixed loaded-version cap.

## Invocation services and recovery

Complete invocations serialize by `(userId, pluginId)` across replicas and versions through renewable
PostgreSQL leases. Waiting is cancellable; FIFO is not promised. No database connection or transaction
stays open throughout plugin execution. Each admission has a unique fencing token. State writes verify
current unexpired ownership atomically; other services check ownership before dispatch.

State is owner/plugin-scoped JSON with 1 MiB records and paginated listing. It exposes no callbacks or CAS.
HTTP accepts any HTTP(S) destination with bounded metadata and 1 MiB bodies, cancellation, and no inherited
credentials, redirects, cookies or automatic retries. OAuth keeps tokens host-owned and permits bounded
streaming responses up to 36 MiB for Gmail. Workspace files use the host sandbox adapter; owner-scoped
S3 file IDs use existing attachment limits and retention without resolving a sandbox. Channel sends use
existing owner-scoped providers; failed/interrupted delivery may have an unknown outcome. Log only bounded
operational messages, never credentials, arguments or stored user data.

Cancellation, timeout and lease loss revoke service access and request cancellation, while actual code
retains resource accounting until it exits. Renewal continues while timed-out code remains active unless
ownership is lost. Lease expiry may admit another invocation while old code still runs; dispatched external
effects cannot be undone by fencing. Never automatically replay a call. Queue timeout means the handler
did not start; invocation timeout means its outcome may be unknown.

All numeric settings use `SOUZ_PLUGINS_` plus these suffixes (milliseconds unless stated):

| Setting suffix | Default |
| --- | ---: |
| `POLL_MILLIS` / `JITTER_MILLIS` | 60000 / 15000 |
| `QUEUE_MILLIS` / `CALL_MILLIS` | 60000 / 60000 |
| `LEASE_MILLIS` / `RENEW_MILLIS` | 90000 / 10000 |
| `PREPARE_MILLIS` / `RETRY_MILLIS` | 30000 / 30000 |
| `CLEANUP_MILLIS` / `GRACE_MILLIS` | 10000 / 30000 |
| `SHUTDOWN_MILLIS` / `UPLOAD_MILLIS` | 30000 / 120000 |
| `USER_FAILURE_THRESHOLD` | 5 consecutive technical failures |
| `VERSION_FAILURE_THRESHOLD` / `VERSION_MINIMUM_USERS` | 10 failures / 3 users |
| `VERSION_WINDOW_MILLIS` / `COOLDOWN_MILLIS` | 60000 / 30000 |
| `SEVERE_INCIDENT_THRESHOLD` | 3 incidents per digest |

The user/plugin breaker is database-coordinated; the version breaker is replica-local. After cooldown,
one ordinary invocation probes recovery. Authorization, validation, business errors and caller cancellation
do not count as technical failures. Stuck work beyond grace or unrecoverable cleanup creates an idempotent
durable severe incident. Successful calls do not reset the count; explicit re-enable does.

Unrecoverable incidents stop intake, persist diagnostics/quarantine, and trigger bounded process shutdown.
A process watchdog forces failure exit if shutdown stalls. Shared services remain open beneath live plugin
code. Deploy with restart-on-failure, as in the repository Compose service. Routine updates and retirement
do not restart the backend. Plugin leases do not implement general agent-execution failover.

## Verification

Run `./gradlew :backend:test :plugin-api:check :plugins:gmail:test :plugins:gmail:jar` with Java 21 and Docker.
The backend tests compile independent Java/Kotlin JARs and use PostgreSQL and MinIO. The packaged Gmail
JAR also runs through the production catalog, including owner isolation and state restoration on another backend. Non-cooperative code
and process termination are checked only in disposable JVMs. Build the independent SDK example using
the [SDK instructions](../../plugin-api/docs/plugin-contract.md). See [Gmail](../../plugins/gmail/docs/gmail-workflow.md)
for synchronization, attachment import and durable notification reservations.
