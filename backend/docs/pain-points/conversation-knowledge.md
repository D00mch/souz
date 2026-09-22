# Conversation Knowledge

## Invariant

`PostgresConversationKnowledgeStore` uses the backend's existing datasource and Flyway schema. The agent writer, `GetKnowledge`, and `SearchKnowledge` share that store. Knowledge operations do not instantiate a sandbox, access application files, or require a Docker socket. File-backed Skill execution has separate filesystem requirements.

Rows belong to an exact user and canonical chat UUID, enforced by a composite foreign key. Writes lock the owned chat until commit, never create a chat, and publish a reference only after commit succeeds. Explicit cleanup is scoped and idempotent; deleting the chat or user cascades to its Knowledge. Archiving retains references. There is no TTL, startup deletion, or automatic import of sandbox records.

`KnowledgeRecordCodec` is shared with desktop storage. It preserves the versioned complete/head-tail representation and UTF-16 offsets while limiting retained UTF-8 content. The database stores serialized JSON as text: JSON escaping preserves control characters such as NUL that PostgreSQL `jsonb` rejects. The shared RE2 retrieval tools search each retained segment separately.

## Failure contract

Absent or invalid conversation scope is unavailable; foreign or nonexistent chats cannot receive writes. Missing or cross-scope records return `knowledge_not_found`. Database and corrupt-record reads produce `storage_failure`; failed writes keep the original tool result inline. Cancellation propagates. No operation falls back to filesystem storage. Oversized subagent results, including exhaustion progress, pass through the parent's ordinary offload path; this does not change the fields kept in immediate context.

## Deployment and safe changes

- Preserve one store binding per host and keep codec changes compatible with existing desktop records. Validate ownership in SQL on every access, retain database cascade cleanup, and test failures at commit as well as connection acquisition.
- Use the existing `POSTGRES_DSN`, `SOUZ_BACKEND_DB_USER`, `SOUZ_BACKEND_DB_PASSWORD`, and `SOUZ_BACKEND_DB_SCHEMA` configuration. Multi-host DSNs require `targetServerType=primary`. No Knowledge volume, extension, or additional connection pool is required.
- Startup migrations use the configured database role. It needs schema usage/create privileges, ownership of `chats` to add its unique constraint, and permission to create the Knowledge table and indexes and maintain Flyway history. Runtime operations require `SELECT`, `INSERT`, and `DELETE` on `conversation_knowledge`, plus `SELECT` and the PostgreSQL row-lock privilege (`UPDATE` on at least one column) on `chats`. The existing backend role normally owns these tables.
- Keep the pod root filesystem read-only. The locally copied `helm/souz/env/default.yaml` supplies database secrets and primary-routing guidance; its existing state volume is not used by Knowledge. The local copy contains environment values only, so chart rendering and the effective inherited security context require the chart templates. No production-cluster access is needed for local validation.

## Verification

From the repository root, with Docker running:

```sh
./gradlew :backend:test --tests 'ru.souz.backend.storage.postgres.PostgresConversationKnowledgeStoreTest' --tests 'ru.souz.backend.e2e.BackendKnowledgeE2eTest' --tests 'ru.souz.backend.app.BackendDiModuleTest'
bash tools/knowledge-readonly-smoke.sh
```

The smoke script creates isolated PostgreSQL and runs the production Knowledge bindings in two separate Java 21 containers with read-only roots, read-only application mounts, an unprivileged UID, and no writable temporary directory or Docker socket. The first writes; its replacement retrieves, searches, and clears the record. Containers and their private network are removed on exit. This checks the Knowledge path, not unrelated filesystem-backed backend tools.
