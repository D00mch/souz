# User file storage

Store immutable files in one private, operator-provisioned S3-compatible bucket. PostgreSQL owns authorization, metadata, source-key deduplication and cleanup deadlines. `object_key` is the unique full key inside the bucket. Require the owner ID for every public lookup. File UUIDs are independent of object keys, and deleting local `users` rows can leave files intact. [Storage implementation](../src/main/kotlin/ru/souz/backend/attachments/AttachmentStorage.kt)

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
| `SOUZ_ATTACHMENTS_MAX_EXECUTION_SAVES` | New workspace objects per execution: `20` |
| `SOUZ_ATTACHMENTS_MAX_EXECUTION_SAVE_BYTES` | Total workspace bytes per execution: `200000000` |
| `SOUZ_ATTACHMENTS_MAX_OWNER_SAVES_PER_24H` | New workspace objects per owner in a rolling 24-hour window: `100` |
| `SOUZ_ATTACHMENTS_MAX_OWNER_SAVE_BYTES_PER_24H` | Total workspace bytes per owner in a rolling 24-hour window: `1000000000` |

JVM properties use `souz.attachments.` plus the lowercase suffix, e.g. `souz.attachments.s3_endpoint`. Grant only `s3:GetObject`, `s3:PutObject`, and `s3:DeleteObject` on `arn:aws:s3:::<bucket>/*`. The backend does not create buckets or manage policies, quotas or lifecycle rules. Verify anonymous GET denial and keep the bucket stable for the lifetime of its files.

## Workspace save budgets

The four save limits take positive integers and apply to `AttachFileToReply(path)`. Deploy the policy and configure the same limits on all replicas. Workspace edits and preparation stay in the sandbox. Selection by ID, image generation, client uploads, and JVM plugin file services use separate paths.

Each replica queues reservations for the same owner before borrowing a database connection. PostgreSQL coordinates admission across replicas and commits the charge with the pending object reservation before the PUT. Each new reservation counts once and charges its file size. The execution budget is shared by the parent, its children, and continuations or retries using the same execution ID. A new execution starts with a fresh execution budget. The owner budget uses PostgreSQL time and includes reservations from all of that owner's executions over the preceding 24 hours.

Within an execution, changing the bytes or filename requires a new reservation. Duplicate requests keep the original reservation's budget accounting. Reservations stay charged after expiry or cleanup, failed or cancelled uploads, discarded or replaced selections, and failed composites or executions. One row stores the count and bytes for the lifetime of each charged execution. The owner ledger survives execution deletion, and the attachment cleanup worker prunes all entries older than the 24-hour window. Accounting starts with reservations admitted through this policy.

On rejection, the tool returns `attachment_execution_save_limit_exceeded` or `attachment_owner_save_limit_exceeded` with guidance to select an existing file or finish the reply. The current reply selection is preserved. Track charged bytes and rejections through the [attachment meters](metrics.md#application-meters). To investigate heavy usage by owner, group `attachment_save_charges` by `user_id` for `admitted_at > statement_timestamp() - interval '24 hours'` and sum `size_bytes`.

## Lifecycle

Reserve metadata before PUT. An identical ready duplicate returns the original file and expiry. Reject conflicting duplicates and return `attachment_not_ready` for pending duplicates without resuming PUT. The service closes the input it reads.

Register completed external uploads after a bounded S3 read. Reject registered foreign keys before reading S3. Concurrent imports converge on the unique key, and repeated owned imports preserve metadata and expiry. Clients must use a fresh key for each upload. Trust shared-bucket writers because the first importer claims each unregistered key.

Failed or cancelled uploads stay pending until the 10-minute reservation expires. Complete only an unexpired pending reservation. Ready files expire 30 days after completion and become inaccessible before physical cleanup. Reads verify size and SHA-256. Lower upload limits apply to new uploads.

Claim one due row at a time and revoke access before deleting its object. Keep each cleanup lease longer than a single S3 call and fence row removal with the current claim. Expired claims allow retries after failure or crash. A successful removal frees the source key. Keep cleanup retries bounded and exception messages and signed requests out of logs. [Cleanup claims](../src/main/kotlin/ru/souz/backend/storage/postgres/PostgresAttachmentRepository.kt) · [Worker cadence](../src/main/kotlin/ru/souz/backend/attachments/AttachmentCleanupWorker.kt)

Configure an operator-managed bucket lifecycle beyond retention plus upload grace (for example 31 days). This removes unregistered external uploads and exceptionally late PUTs arriving after application cleanup; the backend cannot rediscover those orphan objects.

Transfers buffer files in memory. Concurrency limits are not heap-byte budgets: buffering may copy data and callers can retain returned arrays. The S3 client closes after application work stops. [Attachment tools and message references](attachments.md) use this storage through owner-scoped access; this service has no public upload routes.

[Migration V23](../src/main/resources/db/migration/V23__attachment_object_keys.sql) requires an empty `stored_attachments` table. Rollout has no data backfill or legacy WebSocket reference conversion.

Backend credentials are not forwarded to Docker sandboxes. LOCAL child commands inherit the backend environment; LOCAL credential isolation is tracked in [#903](https://github.com/D00mch/souz/issues/903).
