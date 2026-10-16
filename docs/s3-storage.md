# User file storage

The backend stores immutable user files in one private, operator-provisioned S3-compatible bucket per environment. PostgreSQL holds ownership, file IDs, metadata, checksums, idempotency keys and cleanup state. Object keys use `files/<SHA-256 of UTF-8 user ID>/<file UUID>`. The prefix groups objects; authorization is enforced by owner-scoped PostgreSQL lookups, not by the prefix. Storage credentials belong to the backend and are never forwarded to user sandboxes.

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
| `SOUZ_ATTACHMENTS_MAX_FILE_BYTES` | `20000000`, maximum `100000000` |
| `SOUZ_ATTACHMENTS_MAX_CONCURRENT_UPLOADS` | `16`; bounds total in-memory bytes across concurrent uploads |

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

Files expire 30 days after successful storage; retries do not extend that deadline. Access fails at expiry even before physical cleanup. Pending uploads expire after 24 hours. A background worker checks every minute, marks candidates before object deletion, and retries failed deletion. Database tombstones survive user deletion so late writes and remaining objects can be removed. The bucket and tombstone records are retained.

The S3 client is process-owned and closes after application work stops. The implementation uses bounded in-memory transfers; it does not mount S3 or write local files. This layer exposes an internal backend service, without public upload routes, channel adapters, agent tools or model integration.

## Verification

The test fixture uses the public [Coollabs source build of MinIO](https://github.com/coollabsio/minio), pinned to release `RELEASE.2025-10-15T17-29-55Z` and a multi-platform digest in `AttachmentStorageTest.kt`. It supports Linux amd64 and arm64 without registry credentials. Verify a fresh image pull when changing this dependency; a cached image can hide registry access failures.

Run `./gradlew :backend:test --tests 'ru.souz.backend.attachments.*' --tests 'ru.souz.backend.app.BackendRuntimeResourcesTest'` with Docker available. The fixture provisions a bucket and a restricted service account using administrative credentials; backend operations use only the restricted account. Integration tests use real PostgreSQL and MinIO for shared-bucket ownership, anonymous denial, idempotency, expiry, interrupted commits and cleanup after user deletion. Apply the Flyway migration together with the backend release; use a fresh test database for this upstream migration sequence.
