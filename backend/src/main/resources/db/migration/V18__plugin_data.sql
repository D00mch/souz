create table plugin_data (
    user_id text not null references users(id) on delete cascade,
    plugin_id text not null,
    record_key text collate "C" not null check (octet_length(record_key) between 1 and 512),
    value jsonb not null,
    updated_at timestamptz not null default now(),
    primary key (user_id, plugin_id, record_key)
);
