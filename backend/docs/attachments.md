# Attachment contracts

`FileContent` carries an immutable, owner-scoped `fileId`, name, MIME type, size, and presentation. Metadata grants no access and contains no URLs, credentials or host paths. [S3 storage](s3-storage.md) owns availability and retention.

## Tools and selection

With S3 configured, the backend exposes `PrepareAttachment(fileId)`, `AttachFileToReply(path | fileId, presentation)`, and `DetachFileFromReply(fileId)` through the enabled-tool catalog. All three require a DOCKER sandbox; LOCAL workspaces are shared. Text-only Client-Souz executions exclude reply selection.

Preparation rebuilds a sandbox working copy from immutable storage. Attachment accepts exactly one source; saving the same bytes and name within an execution reuses the object. Detaching only changes reply selection. Prepared copies survive S3 expiry; workspace cleanup and save quotas are separate policies.

Message `files` describe available artifacts; trusted tool `replyFiles` and `detachedReplyFiles` change selection in call order. Subagents return files without selecting the parent's reply. Knowledge offloading preserves structured metadata. [Composite commands](../../docs/composite-skill-commands.md) define success/failure handling.

A reply allows 10 files and 100 MB total. An over-limit tool result returns `attachment_reply_limit_exceeded` and leaves selection unchanged; saved artifacts remain available. Tools validate ownership, presentation and sandbox paths, return safe error codes, and propagate cancellation. Unexpected failures are logged without exception messages or transport secrets.

## History and persistence

Before chat and summarization, non-tool message files are rendered into request text so models can use their IDs. Stored history retains structured references. Compaction keeps recent unique references in last-occurrence order, bounded by positive `SOUZ_ATTACHMENTS_HISTORY_LIMIT` / `souz.attachments.historyLimit` (default 20) for parent and child loops. Active exchanges and reply selection remain intact; older files remain usable by known ID but cannot be enumerated.

Selection lives in context/session and resumes only for a matching non-null execution ID. A different or missing ID starts empty. Persist the resulting selection without merging the old snapshot back in.

The final transaction validates file ownership, availability, metadata, presentation and limits, then commits text, attachments, session and execution status together. Unavailable input files fail admission; unavailable final reply files are dropped without losing the answer. Trusted message reads expose metadata; [image preparation and generation](images.md) happen at the provider/tool boundary. Upload/download routes and transport support are outside this layer.

Session schema 2 reads schema 1. Empty attachment fields are omitted, keeping text-only snapshots readable by older releases. Rolling back to a release without attachment support breaks sessions containing file references.
