# Pain points

## User file storage

See [User file storage](s3-storage.md) for the storage contract and verification command.

- Use only object read/write/delete permissions on the configured bucket. Bucket provisioning and policies belong to the operator.
- Per-user storage quotas are not implemented. The upload file-size limit and shared save/read concurrency limit do not cap a user's total stored bytes; all users share the bucket's capacity. User quotas require a separate task with atomic accounting and reservations for concurrent uploads if needed.
- User IDs are owned by the external identity system. Removing a local `users` row does not hide files, trigger cleanup or reset retention deadlines.
- Resolve ownership and expiry on every read. An opaque file ID or matching checksum does not grant access to another user's data.
- Keep database metadata and S3 objects recoverable across partial failures: reserve metadata before PUT, mark expiration before DELETE, and retain tombstones after file expiration. A late writer must not leave an object beyond cleanup's reach.
- The save/read concurrency limit bounds active operations, not total heap bytes: input and transport buffering add copies, and callers may retain returned arrays.
- Backend secret isolation from command environments is Docker-mode-specific. LOCAL children inherit backend environment variables; see the follow-up [#903](https://github.com/D00mch/souz/issues/903).
- Never log S3 exception bodies or credentials. Cleanup must retry without exposing signed transport details.
- Keep this layer independent of channels, model payloads and sandbox working copies.
- Test MinIO images must be anonymously pullable on a clean CI runner and pinned by a multi-platform digest. A cached local image does not verify registry availability.
