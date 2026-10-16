# User file storage

The backend stores immutable user files in one private, operator-provisioned S3-compatible bucket per environment. PostgreSQL holds ownership, file IDs, metadata, checksums, idempotency keys and cleanup state. Object keys use `files/<SHA-256 of UTF-8 user ID>/<file UUID>`. The prefix groups objects; authorization is enforced by owner-scoped PostgreSQL lookups, not by the prefix. Storage credentials belong to the backend and are not forwarded to Docker user sandboxes by the backend. In LOCAL mode, child commands inherit the backend environment, including credentials configured there; the Docker isolation guarantee does not apply.

## Configuration

Without `SOUZ_ATTACHMENTS_S3_ENDPOINT`, the storage service and cleanup worker are not registered. With a configured connection, storage is available for every existing user; there is no per-user feature switch.

| Variable | Meaning / default |
| --- | --- |
| `SOUZ_ATTACHMENTS_S3_ENDPOINT` | HTTP(S) S3 origin without credentials, query or path |
| `SOUZ_ATTACHMENTS_S3_ACCESS_KEY` | Required server credential |
| `SOUZ_ATTACHMENTS_S3_SECRET_KEY` | Required server secret |
| `SOUZ_ATTACHMENTS_S3_REGION` | `us-east-1` |
| `SOUZ_ATTACHMENTS_S3_PATH_STYLE` | `true`, suitable for MinIO |
| `SOUZ_ATTACHMENTS_S3_BUCKET` | Required name of an existing private bucket |
| `SOUZ_ATTACHMENTS_MAX_FILE_BYTES` | Upload limit: `20000000`, maximum `100000000`; does not restrict reads |
| `SOUZ_ATTACHMENTS_MAX_CONCURRENT_UPLOADS` | `16`; shared limit of active saves and reads, not a heap-byte limit |

Equivalent JVM properties use `souz.attachments.` plus the lowercase suffix, for example `souz.attachments.s3_endpoint`. The service account needs only `s3:GetObject`, `s3:PutObject` and `s3:DeleteObject` on objects in that bucket. The backend does not create buckets, change policies or configure quotas/lifecycle. A missing bucket causes storage operations to fail; it is not created automatically. The bucket must not have public-read policies; verify anonymous GET denial in the deployment. File IDs are not download URLs or access credentials.

Example service-account policy (replace `souz-files` with the configured bucket):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
    "Resource": ["arn:aws:s3:::souz-files/*"]
  }]
}
```

Bucket administration remains with the operator. Per-user quotas are not implemented. Keep the bucket configuration stable for the lifetime of stored files; changing it does not move objects.

## Internal operations

`AttachmentStorage.save(userId, sourceKey, name, input)` returns a `StoredAttachment`. Reusing a source key for identical content and metadata returns the same file; different content or metadata fails. `get`, `find` and `read` resolve files within the owner scope. Filenames are sanitized, MIME is detected from bytes, and reads verify size and SHA-256. Source keys must be stable per input operation and are scoped to the user.

Files expire 30 days after successful storage; retries do not extend that deadline. Access fails at expiry even before physical cleanup. Pending uploads expire after 24 hours. A background worker checks every minute, marks candidates before object deletion, and retries failed deletion. Database tombstones retain cleanup state for late writes and prevent expired source keys from being reused. Removing or recreating a local `users` row does not affect file access or retention: the external user ID identifies the owner. The bucket and tombstone records are retained.

Cleanup uses three partial indexes and one atomic SQL statement to select expired ready files, stale pending uploads and deletion retries. Each branch selects at most the batch limit in index order; the combined batch is limited to 100 files. Up to 300 candidate rows may be locked until the transaction commits. Processing order is not a storage contract; the final merge sorts only the bounded candidates.

The S3 client is process-owned and closes after application work stops. Saves and reads share one semaphore per storage instance, held until the operation completes or fails; waiting is cancellable. Files are buffered in memory. The configured per-file size limit applies to uploads only; reads have no separate size cap and validate the object size and SHA-256 against stored metadata. Lowering the upload limit does not make previously stored files unreadable. Uploads use a repeatable content provider over the existing byte array, avoiding the SDK copy made by `RequestBody.fromBytes`; every retry opens a fresh stream. Input buffering and the URLConnection transport can still allocate additional buffers, so `MAX_CONCURRENT_UPLOADS × MAX_FILE_BYTES` is not a bound on heap consumption. Arrays returned by `read` can outlive their permit and remain the caller's responsibility. The implementation does not mount S3 or write local files. This layer exposes an internal backend service, without public upload routes, channel adapters, agent tools or model integration.

## Sandbox secret isolation

Use Docker sandbox mode for the documented separation of backend credentials from command environments. LOCAL is the default mode and its child processes inherit the backend environment.

TODO ([#903](https://github.com/D00mch/souz/issues/903)): define and enforce an explicit environment policy for LOCAL commands, with tests using synthetic credentials. Changes to LOCAL execution and future in-process JVM plugin isolation are outside this storage implementation.

## Verification

The test fixture uses the public [Coollabs source build of MinIO](https://github.com/coollabsio/minio), pinned to release `RELEASE.2025-10-15T17-29-55Z` and a multi-platform digest in `AttachmentStorageTest.kt`. It supports Linux amd64 and arm64 without registry credentials. Verify a fresh image pull when changing this dependency; a cached image can hide registry access failures.

Run `./gradlew :backend:test --tests 'ru.souz.backend.attachments.*' --tests 'ru.souz.backend.app.BackendRuntimeResourcesTest'` with Docker available. The fixture provisions a bucket and a restricted service account using administrative credentials; backend operations use only the restricted account. Integration tests use real PostgreSQL and MinIO for shared-bucket ownership, anonymous denial, idempotency, expiry, interrupted commits, late-write cleanup and retention independent of local user rows. Apply the Flyway migration together with the backend release; use a fresh test database for this upstream migration sequence.
