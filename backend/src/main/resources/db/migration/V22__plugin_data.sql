create table plugin_data (
    user_id text not null references users(id) on delete cascade,
    plugin_id text not null,
    record_key text collate "C" not null,
    value jsonb not null,
    primary key (user_id, plugin_id, record_key)
);
