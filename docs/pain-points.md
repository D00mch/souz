# Pain points

## User file storage

See [User file storage](s3-storage.md) for the storage contract and verification command.

- Resolve ownership and expiry on every read. An opaque file ID or matching checksum does not grant access to another user's data.
- Keep database metadata and S3 objects recoverable across partial failures: reserve metadata before PUT, mark expiration before DELETE, and retain tombstones after account deletion. A late writer must not leave an object beyond cleanup's reach.
- Never log S3 exception bodies or credentials. Cleanup must retry without exposing signed transport details.
- Keep this layer independent of channels, model payloads and sandbox working copies.
