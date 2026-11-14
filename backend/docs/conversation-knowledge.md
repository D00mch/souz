# Conversation Knowledge

## Invariants and safe changes

- Use the same [PostgreSQL Knowledge store](../src/main/kotlin/ru/souz/backend/storage/postgres/PostgresConversationKnowledgeStore.kt) for writers and retrieval. Keep Knowledge independent of the sandbox filesystem.
- Every access uses exact user/chat ownership. Writes lock the owned chat through commit and publish references only afterward. They never create chats or overwrite UUID records, including on collision. Cleanup is scoped and idempotent; chat/user deletion cascades, while archiving retains records. There is no TTL, startup cleanup, or sandbox import.
- Preserve desktop compatibility through the [record codec](../../sharedLogic/src/commonJvmMain/kotlin/ru/souz/knowledge/KnowledgeRecordCodec.kt) and the [shared retention and search contract](../../sharedLogic/docs/runtime-sandbox-and-skills.md). Store serialized JSON as text because PostgreSQL `jsonb` rejects NUL.
- Missing/invalid scope is unavailable; foreign/nonexistent chats reject writes, and missing/cross-scope records return `knowledge_not_found`. Database/corrupt-record reads return `storage_failure`; failed writes retain the original result inline. Cancellation propagates. Test commit failures as well as connection failures.

## Deployment

Reuse the existing database configuration; multi-host DSNs require `targetServerType=primary`. Knowledge needs no volume, extension, extra pool, writable temporary directory, or Docker socket.

The migration role needs schema usage/create privileges, ownership of `chats`, and permission to create tables/indexes and maintain Flyway history. Runtime requires `SELECT`, `INSERT`, `DELETE` on `conversation_knowledge`, plus `SELECT` and row-lock permission (`UPDATE` on a column) on `chats`. The existing backend role normally owns these tables.

## Verification

With Docker running, from the repository root:

```sh
./gradlew :backend:test --tests '*Knowledge*Test' --tests '*BackendDiModuleTest'
bash tools/knowledge-readonly-smoke.sh
```

The [smoke script](../../tools/knowledge-readonly-smoke.sh) verifies that Knowledge survives container replacement with unprivileged containers, read-only filesystems and PostgreSQL as the only writable service.
