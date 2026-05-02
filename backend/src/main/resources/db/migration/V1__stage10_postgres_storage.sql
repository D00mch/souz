create table users (
  id text primary key,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz
);

create table user_settings (
  user_id text primary key references users(id) on delete cascade,
  settings_json jsonb not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table chats (
  id uuid primary key,
  user_id text not null references users(id) on delete cascade,
  title text,
  archived boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index chats_user_updated_idx
on chats(user_id, updated_at desc);

create table messages (
  id uuid primary key,
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  seq bigint not null,
  role text not null,
  content text not null,
  metadata jsonb not null default '{}',
  created_at timestamptz not null default now(),
  unique(user_id, chat_id, seq)
);

create index messages_chat_seq_idx
on messages(user_id, chat_id, seq desc);

create table agent_conversation_state (
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  context_json jsonb not null,
  based_on_message_seq bigint not null,
  updated_at timestamptz not null default now(),
  row_version bigint not null default 0,
  primary key(user_id, chat_id)
);

create table agent_executions (
  id uuid primary key,
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  user_message_id uuid references messages(id),
  assistant_message_id uuid references messages(id),
  status text not null,
  request_id text,
  client_message_id text,
  model text,
  provider text,
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  cancel_requested boolean not null default false,
  error_code text,
  error_message text,
  usage_json jsonb,
  metadata jsonb not null default '{}'
);

create index agent_executions_chat_started_idx
on agent_executions(user_id, chat_id, started_at desc);

create unique index agent_executions_one_active_per_chat_idx
on agent_executions(user_id, chat_id)
where status in ('queued', 'running', 'waiting_option', 'cancelling');

create table options (
  id uuid primary key,
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  execution_id uuid not null references agent_executions(id) on delete cascade,
  kind text not null,
  title text,
  selection_mode text not null,
  options_json jsonb not null,
  payload_json jsonb not null default '{}',
  status text not null,
  answer_json jsonb,
  created_at timestamptz not null default now(),
  expires_at timestamptz,
  answered_at timestamptz
);

create index options_execution_idx
on options(user_id, chat_id, execution_id);

create index options_status_idx
on options(user_id, status);

create table agent_events (
  id uuid primary key,
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  execution_id uuid references agent_executions(id) on delete cascade,
  seq bigint not null,
  type text not null,
  payload jsonb not null,
  created_at timestamptz not null default now(),
  unique(user_id, chat_id, seq)
);

create index agent_events_chat_seq_idx
on agent_events(user_id, chat_id, seq);
