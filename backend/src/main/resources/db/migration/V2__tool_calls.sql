create table tool_calls (
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  execution_id uuid not null references agent_executions(id) on delete cascade,
  tool_call_id text not null,
  name text not null,
  status text not null,
  arguments_json jsonb not null default '{}',
  result_preview text,
  error text,
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  duration_ms bigint,
  primary key(user_id, chat_id, execution_id, tool_call_id)
);

create index tool_calls_execution_idx
on tool_calls(user_id, chat_id, execution_id);
