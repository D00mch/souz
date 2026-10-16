-- Ownership uses the external user ID; local users row deletion is not file deletion.
create table stored_attachments (
    id uuid primary key,
    user_id text not null,
    source_key text not null,
    name text not null,
    mime_type text not null,
    size_bytes bigint not null check (size_bytes >= 0),
    sha256 text not null,
    state text not null check (state in ('pending', 'ready', 'deleting', 'deleted')),
    created_at timestamptz not null,
    stored_at timestamptz,
    expires_at timestamptz,
    cleanup_attempt_at timestamptz,
    unique (user_id, source_key),
    check (state <> 'ready' or (stored_at is not null and expires_at is not null))
);

-- Match each cleanup branch's predicate and ordering; deleted history stays outside these indexes.
create index stored_attachments_ready_expiry_idx on stored_attachments(expires_at) where state = 'ready';
create index stored_attachments_pending_created_idx on stored_attachments(created_at) where state = 'pending';
create index stored_attachments_deleting_queue_idx
    on stored_attachments((coalesce(cleanup_attempt_at, created_at)), created_at, id) where state = 'deleting';
