create table history_memory_fragments (
  id uuid primary key,
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  first_seq bigint not null,
  source_ids uuid[] not null check (cardinality(source_ids) between 1 and 16),
  created_at timestamptz not null,
  available_at timestamptz not null,
  payload jsonb,
  attempts integer not null default 0,
  lease_token uuid,
  lease_until timestamptz,
  completed_at timestamptz,
  unique(chat_id, first_seq)
);

create index history_memory_pending_idx on history_memory_fragments(available_at)
where completed_at is null;

create index history_memory_chat_pending_idx on history_memory_fragments(chat_id, first_seq)
where completed_at is null;
