# Attachment contracts

[FileContent](../../llms/src/main/kotlin/ru/souz/llms/FileContent.kt) carries an immutable, owner-bound file reference. Require owner-scoped lookup for access and keep URLs, credentials, and host paths out of message metadata. [Attachment storage](../src/main/kotlin/ru/souz/backend/attachments/AttachmentStorage.kt) · [S3 configuration and retention](s3-storage.md)

## Tools and selection

With S3 configured, the enabled-tool catalogue exposes preparation, attachment, and detachment. Preparing a working copy or saving a workspace file requires Docker isolation because LOCAL workspaces are shared. Selecting an available stored file by ID uses owner-scoped metadata in either mode without resolving a sandbox. Detachment changes only the selection and needs neither storage nor a sandbox, even after expiry. [Attachment tools](../src/main/kotlin/ru/souz/backend/attachments/AttachmentTools.kt) · [Workspace access](../src/main/kotlin/ru/souz/backend/attachments/BackendAttachmentAccess.kt)

Message `files` describe available artifacts. Only trusted tool results change reply selection, in call order. Reject an over-limit selection change as a whole and keep saved artifacts available. Subagents return references without selecting the parent's reply. Propagate cancellation and keep raw exception messages and transport secrets out of tool errors and logs. [Selection rules and limits](../../agent/src/main/kotlin/ru/souz/agent/nodes/ReplyFileSelection.kt) · [Composite commands](../../docs/composite-skill-commands.md)

## WebSocket transport

Resolve the receipt before importing uploads so accepted retries survive expiry and storage outages. Revalidate ownership and availability under row locks before committing input. Initial and follow-up validation precede execution, message, device-context, and receipt mutations. Publish input to the runtime only after the message commits. [Submit handling](../src/main/kotlin/ru/souz/backend/client/PublicClientService.kt) · [Commit validation](../src/main/kotlin/ru/souz/backend/storage/postgres/MessageAttachmentValidation.kt)

Commit the reply descriptor snapshot with the assistant message, session, and execution status while holding attachment row locks. Drop unavailable reply files and retain the answer. Build terminal events from the saved snapshot. Persist descriptors before publishing terminal events or channel pushes so replay survives file cleanup. File forwarding is limited to public-client channels. [Reply commit](../src/main/kotlin/ru/souz/backend/storage/postgres/PostgresAgentExecutionRepository.kt) · [Channel delivery](../src/main/kotlin/ru/souz/backend/channels/ChannelDeliveryService.kt) · [Public attachment contract](../../docs/public-souz-contract/README.md#attachments)

## History and persistence

Keep references structured in history. Compaction carries a bounded set of recent references forward and preserves the active exchange. Resume reply selection only for a matching non-null execution ID. Replace the saved selection with the resulting selection so removals survive. [Compaction](../../agent/src/main/kotlin/ru/souz/agent/nodes/NodesSummarization.kt) · [Session restore](../src/main/kotlin/ru/souz/backend/agent/runtime/conversation/BackendConversationRuntime.kt)

Session schema 2 reads schema 1. Omit empty attachment fields for text-only compatibility. Rollback requires a release that can decode any stored file references. [Session schema](../src/main/kotlin/ru/souz/backend/agent/session/AgentConversationSession.kt)

## Verification

For runtime changes, run `./gradlew :backend:test` with Docker. Cover [expiry, retry, and replay](../src/test/kotlin/ru/souz/backend/e2e/BackendPublicAttachmentsE2eTest.kt) and [selection and sandbox isolation](../src/test/kotlin/ru/souz/backend/e2e/BackendAttachmentContractsE2eTest.kt).
