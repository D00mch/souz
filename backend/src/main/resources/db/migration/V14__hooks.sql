-- Receipts double as the durable queue. Their UUID is reserved for the execution before dispatch.
create table hook_receipts (
  id uuid primary key,
  ordinal bigserial unique,
  hook_id text not null,
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  event_key text,
  payload_hash text not null,
  payload text not null,
  prompt text not null,
  revision text not null,
  status text not null default 'pending' check (status in ('pending', 'running', 'finished', 'failed')),
  error_code text,
  created_at timestamptz not null default now(),
  dispatched_at timestamptz,
  llm_calls integer not null default 0,
  total_tokens bigint not null default 0,
  unique (hook_id, event_key)
);
create index hook_receipts_pending_idx on hook_receipts(ordinal) where status in ('pending', 'running');
create index hook_receipts_user_created_idx on hook_receipts(user_id, created_at);

-- Reserve before provider calls; a process crash cannot refund or reset this budget.
create table hook_daily_usage (
  user_id text not null references users(id) on delete cascade,
  day date not null,
  llm_calls integer not null default 0,
  primary key (user_id, day)
);
