# User file storage

`AttachmentStorage` stores immutable user files in one private, operator-provisioned S3-compatible bucket. PostgreSQL owns authorization, metadata, source-key deduplication and cleanup deadlines. Object keys are `files/<SHA-256 of user ID>/<file UUID>`; the prefix is organization, not authorization. Every public lookup requires the owner ID. Local `users` rows may be removed without deleting files.

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

Failed or cancelled uploads remain pending for the existing 10-minute reservation lifetime. Completion succeeds only while the reservation is pending and before that deadline. This is longer than the S3 client's 120-second call timeout. Successful files expire 30 days after completion. `get`, `find` and `read` reject expired files before physical cleanup. Reads verify stored size and SHA-256; lowering the upload limit does not invalidate existing files.

One indexed `cleanup_due_at` schedules pending expiry, ready expiry and deletion retries. Cleanup atomically marks at most 100 due rows `deleting`, revoking access, then deletes each S3 object before removing its database row. Either deletion may be retried after failure or a crash; no tombstone remains after success, and the source key can be reused. A full successful batch drains immediately; partial/empty batches and failures wait 60 seconds. Logs contain file IDs, exception classes and S3 status/error codes, never exception messages or signed requests.

Configure an operator-managed bucket lifecycle beyond retention plus upload grace (for example 31 days). This removes exceptionally late PUTs arriving after application cleanup; the backend cannot rediscover those orphan objects. Database locks end before S3 deletion, so multiple backend workers can attempt the same deletion. Coordination and a separate worker are tracked in [#911](https://github.com/D00mch/souz/issues/911).

Transfers buffer files in memory. Concurrency limits are not heap-byte budgets: buffering may copy data and callers can retain returned arrays. Per-user quotas are outside this service. The S3 client closes after application work stops. This layer has no public upload routes, channel adapters, agent tools or model integration.

Backend credentials are not forwarded to Docker sandboxes. LOCAL child commands inherit the backend environment; LOCAL credential isolation is tracked in [#903](https://github.com/D00mch/souz/issues/903).

