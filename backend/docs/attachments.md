# Attachment contracts

`FileReference.fileId` identifies an immutable, owner-scoped [S3 object](s3-storage.md). `FileContent` adds a display name, detected MIME type, byte size, and presentation (`document`, `image`, `voice`, or `audio`). References contain no credentials, URLs, provider upload IDs, or host paths. Metadata in a message does not grant access or extend retention.

## Messages and model context

Agent messages retain text and optional structured `files`, `replyFiles`, and `detachedReplyFiles`. `files` describe available artifacts; trusted tool results add or remove reply selections in call order. Knowledge offloading changes only content. Active-run input carries its own files. Subagents return their selected files to the parent without selecting the parent's reply.

Provider requests are text-only. Before each chat or summarization call, the agent appends a `[Files available by fileId]` list (name, ID, MIME type, size, presentation) to every non-tool message with `files`; tool results already carry their metadata in JSON. Stored history keeps only the structured references.

Compaction keeps the latest unique completed-prefix references on the summary, ordered by last occurrence and bounded by the server-wide `SOUZ_ATTACHMENTS_HISTORY_LIMIT` / `souz.attachments.historyLimit` (positive integer, default 20) for parent and child model loops. The active exchange and current reply selection stay intact. A reference dropped from context keeps its stored object and retention; a known, available, owned ID can still be prepared, but older files cannot be enumerated.

## Tools

With S3 configured, the backend catalog includes three `FILES` tools, subject to the ordinary enabled-tool snapshot. Text-only Client-Souz executions exclude the reply selection tools.

- `PrepareAttachment(fileId)` checks ownership and availability, then rebuilds a working copy under `attachments/<fileId>/` in the user's sandbox workspace and returns `attachment` and `path`. Editing the copy never changes the S3 object.
- `AttachFileToReply(path | fileId, presentation)` accepts exactly one source, saves a completed sandbox file or selects an available owned object, and returns structured metadata plus the reply selection. It sends no channel message. Repeated registration of the same bytes and name in one execution reuses the saved object.
- `DetachFileFromReply(fileId)` removes a reference from the current execution's selection without reading or deleting the stored file.

The tools run only in DOCKER sandboxes, the only mode with per-user workspaces. LOCAL sandboxes share the backend process directories, so every tool returns `attachment_sandbox_unsupported` there and writes nothing.

Tool failures return a safe `error` code, such as `attachment_path_forbidden`, `attachment_path_not_found`, `attachment_source_required`, or `attachment_presentation_mismatch`. Unexpected storage or database failures are logged with their exception class and S3 status only and return `attachment_operation_failed`. Cancellation propagates. Paths resolve through the invocation's sandbox; unsafe paths and symbolic links are rejected. No S3 credentials enter tool results or sandbox files.

Saving reads and hashes the sandbox file once while holding a storage transfer permit. Prepared copies are workspace files: S3 expiry and cleanup do not remove them.

[Composite commands](../../docs/composite-skill-commands.md) can pass prepared paths and saved IDs between steps.

## Limits and commit

A message holds at most 10 files and 100 MB in total (`MAX_MESSAGE_FILES`, `MAX_MESSAGE_FILE_BYTES`). Reply selection applies these limits to the resulting set: a tool result that would exceed them is rejected as a whole, its content becomes `attachment_reply_limit_exceeded` with the current selection size, and the selection stays unchanged. Its saved `files` remain available.

PostgreSQL stores message attachments separately from text. Commits check ownership, ready/unexpired state, metadata, presentation, uniqueness, and limits in one locked query. Incoming user messages fail on an unavailable file. The final assistant commit drops a selected file that expired or was cleaned up meanwhile and keeps the answer. Final text, attachments, continuation state, and execution status commit together. Trusted HTTP message reads include attachment metadata. This layer exposes no upload/download routes and does not extend the text-only Client-Souz WebSocket contract.

## Sessions

Session schema 2 reads schema 1 text snapshots and rejects unsupported versions. History and reply selections persist with the session. The host restores selection before tools run only for a matching non-null execution ID, then saves the authoritative result; a different or missing ID starts empty.

Sessions without a reply selection omit `attachmentExecutionId` and `replyAttachments`, and messages without files omit their file fields, so a release without attachment support can read them. A session containing file references cannot be read by such a release; rolling back past this schema breaks those chats.

## Verification

Run `./gradlew :llms:test :agent:test :sharedLogic:test :backend:test` with Docker available. `BackendAttachmentContractsE2eTest` uses the production DI graph, the production sandbox factory, PostgreSQL and MinIO for sandbox preparation, reply persistence and limits, session reconstruction, LOCAL refusal, and owner/expiry validation.
