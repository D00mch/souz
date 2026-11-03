# User file storage

`AttachmentStorage` stores immutable user files in one private, operator-provisioned S3-compatible bucket. PostgreSQL owns authorization, metadata, source-key deduplication and cleanup deadlines. The mandatory, unique `object_key` column stores the full key inside the configured bucket. Backend uploads use `files/<SHA-256 of user ID>/<file UUID>`; external uploads may use any opaque key. Internal UUIDs are independent of external keys; neither the key nor its prefix grants authorization. Every public lookup requires the owner ID. Local `users` rows may be removed without deleting files.

## Configuration

Without `SOUZ_ATTACHMENTS_S3_ENDPOINT`, neither storage nor its cleanup worker is registered.

| Variable | Meaning / default |
| --- | --- |
| `SOUZ_ATTACHMENTS_S3_ENDPOINT` | HTTP(S) origin, without credentials, query or path |
| `SOUZ_ATTACHMENTS_S3_BUCKET` | Required existing private bucket |
| `SOUZ_ATTACHMENTS_S3_ACCESS_KEY` | Required backend credential |
| `SOUZ_ATTACHMENTS_S3_SECRET_KEY` | Required backend secret |
| `SOUZ_ATTACHMENTS_S3_REGION` | `us-east-1` |
| `SOUZ_ATTACHMENTS_S3_PATH_STYLE` | `true` (MinIO compatible) |
| `SOUZ_ATTACHMENTS_MAX_FILE_BYTES` | Upload limit: `20000000`, maximum `100000000` |
| `SOUZ_ATTACHMENTS_MAX_CONCURRENT_UPLOADS` | `16`; shared concurrency limit for saves and reads |

JVM properties use `souz.attachments.` plus the lowercase suffix, e.g. `souz.attachments.s3_endpoint`. Grant only `s3:GetObject`, `s3:PutObject`, and `s3:DeleteObject` on `arn:aws:s3:::<bucket>/*`. The backend does not create buckets or manage policies, quotas or lifecycle rules. Verify anonymous GET denial and keep the bucket stable for the lifetime of its files.

## Lifecycle

`save(userId, sourceKey, name, input)` sanitizes the name, detects MIME from bytes, and reserves metadata before PUT. A ready duplicate with identical content and metadata returns the original file and expiry; a conflicting duplicate fails. Pending duplicates return `attachment_not_ready` and never resume PUT. The service consumes and closes the input when reading it.

`importUpload(userId, objectKey, name)` registers a completed external upload after a bounded S3 read and MIME/checksum detection, assigning an independent UUID. Keys must be nonblank, contain no control characters and fit in 1,024 UTF-8 bytes. Concurrent imports converge on the unique key; registered foreign keys are rejected before reading S3. Reusing an owned key preserves its metadata and expiry without overwriting the object. Clients must not overwrite or reuse uploaded keys. Unregistered uploads belong to the first importing user, so shared-bucket writers must be trusted. `source_key` deduplicates backend save operations; it is not an S3 address.

Failed or cancelled uploads remain pending for the existing 10-minute reservation lifetime. Completion succeeds only while the reservation is pending and before that deadline. This is longer than the S3 client's 120-second call timeout. Successful files expire 30 days after completion. `get`, `find` and `read` reject expired files before physical cleanup. Reads verify stored size and SHA-256; lowering the upload limit does not invalidate existing files.

One indexed `cleanup_due_at` schedules pending expiry, ready expiry and cleanup leases. Each cleanup pass claims due rows one at a time until none is due: a claim marks the row `deleting`, revoking access, and moves `cleanup_due_at` five minutes ahead, beyond one S3 call. Replicas skip leased rows. The claimer deletes the S3 object, then removes the row only while `cleanup_due_at` still holds its lease, so a stale claim never removes a re-claimed row. After a failure or crash, either deletion is retried once the lease expires; no tombstone remains after success, and the source key can be reused. Three consecutive failed deletions end a pass early, and the worker waits 60 seconds after every pass, so an unavailable S3 costs each replica at most three attempts per minute. Logs contain file IDs, exception classes and S3 status/error codes, never exception messages or signed requests.

Configure an operator-managed bucket lifecycle beyond retention plus upload grace (for example 31 days). This removes unregistered external uploads and exceptionally late PUTs arriving after application cleanup; the backend cannot rediscover those orphan objects.

Transfers buffer files in memory. Concurrency limits are not heap-byte budgets: buffering may copy data and callers can retain returned arrays. Per-user quotas are outside this service. The S3 client closes after application work stops. [Attachment tools and message references](attachments.md) use this storage through owner-scoped access; this service has no public upload routes.

Schema migration `V23` adds the mandatory address column and requires an empty `stored_attachments` table; data backfill and legacy WebSocket reference conversion are outside this rollout.

Backend credentials are not forwarded to Docker sandboxes. LOCAL child commands inherit the backend environment; LOCAL credential isolation is tracked in [#903](https://github.com/D00mch/souz/issues/903).
