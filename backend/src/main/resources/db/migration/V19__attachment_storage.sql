-- Keep storage tombstones after account deletion so objects can still be removed.
create table attachment_buckets (
    user_id text primary key,
    bucket text not null unique,
    ready boolean not null default false
);

create table stored_attachments (
    id uuid primary key,
    user_id text not null references attachment_buckets(user_id),
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

create index stored_attachments_cleanup_idx on stored_attachments(state, expires_at, created_at);
