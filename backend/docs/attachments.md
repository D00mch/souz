# Attachment contracts

`FileReference.fileId` identifies an immutable, owner-scoped [S3 object](s3-storage.md). `FileContent` adds a display name, detected MIME type, byte size, and presentation (`document`, `image`, `voice`, or `audio`). References contain no credentials, URLs, provider upload IDs, or host paths. Metadata in a message does not grant access or extend retention.

Agent messages retain text and optional structured `files`, `images`, `replyFiles`, and `detachedReplyFiles`. `files` describe available artifacts; trusted tool results add or remove reply selections in call order. Knowledge offloading changes only content, and compaction retains file references on the summary. Active-run input carries its own files. Subagents return their selected files to the parent without selecting the parent's reply automatically.

With S3 configured, the backend catalog includes three `FILES` tools, subject to the ordinary enabled-tool snapshot. Text-only Client-Souz executions exclude reply selection tools:

- `PrepareAttachment(fileId)` checks ownership and availability, then rebuilds a working copy under the user's existing sandbox workspace. Its result includes `attachment` and `path`. Editing the copy never changes the S3 object.
- `AttachFileToReply(path | fileId, presentation)` accepts exactly one source, saves a completed sandbox file or selects an available owned object, and returns structured metadata plus the reply selection. It does not send a channel message. Repeated registration of the same bytes and name in one execution reuses the saved object.
- `DetachFileFromReply(fileId)` removes a reference from the current execution's selection without reading or deleting the stored file. Unknown or expired references can be deselected safely; this grants no file access.

`AgentAttachmentAccess` keeps authorization, storage, and sandbox resolution in the host. Cancellation propagates; backend errors exposed to tools contain safe codes. The backend resolves every path using the invocation's sandbox and rejects unsafe paths and symbolic links. No S3 credentials enter tool results or sandbox files.

Composite commands can pass `${prepared.path}` or `${saved.file.fileId}` into later tools. Completed artifacts remain available after a later failure or timeout. Reply additions/removals are published only after the whole composite succeeds, preserving any earlier selection on failure. This does not roll back stored files or other tool effects. Legacy image attachments come only from the last tool step of a successful command; failure or an imageless last tool drops them.

PostgreSQL stores message attachments separately from text. Message commits validate ownership, ready/unexpired state, metadata, presentation, uniqueness, and limits (10 files and 100 MB total) in the same transaction. Trusted HTTP message reads include attachment metadata. This layer does not expose upload/download routes or extend the text-only Client-Souz WebSocket contract.

Session schema 2 reads schema 1 text snapshots and rejects unsupported versions. History and reply selections persist with the session. The host restores selection before tools run only for a matching non-null execution ID, then saves the authoritative result; a different or missing ID starts empty. Final assistant text, attachments, continuation state, and execution status commit together.

## Verification

Run `./gradlew :llms:test :agent:test :sharedLogic:test :backend:test` with Docker available. `BackendAttachmentContractsE2eTest` uses the production DI graph, PostgreSQL and MinIO for sandbox preparation, reply persistence, session reconstruction and owner/expiry validation.
