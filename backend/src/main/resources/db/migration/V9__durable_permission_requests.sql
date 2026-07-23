drop index if exists agent_executions_one_active_per_chat_idx;

create unique index agent_executions_one_active_per_chat_idx
on agent_executions(user_id, chat_id)
where status in ('queued', 'running', 'waiting_option', 'waiting_permission', 'cancelling');

create table permission_requests (
  id uuid primary key,
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  execution_id uuid not null references agent_executions(id) on delete cascade,
  invocation_id uuid not null,
  tool_call_id text,
  tool_name text not null,
  description text not null,
  display_params_json jsonb not null default '{}',
  prompt_hash text not null,
  status text not null,
  created_at timestamptz not null default now(),
  decided_at timestamptz,
  constraint permission_requests_status_check
    check (status in ('pending', 'granted', 'denied', 'cancelled')),
  constraint permission_requests_display_params_object_check
    check (jsonb_typeof(display_params_json) = 'object'),
  constraint permission_requests_decision_timestamp_check
    check (
      (status = 'pending' and decided_at is null)
      or (status <> 'pending' and decided_at is not null)
    ),
  unique(execution_id, invocation_id)
);

create unique index permission_requests_one_pending_per_execution_idx
on permission_requests(execution_id)
where status = 'pending';

create index permission_requests_owned_pending_idx
on permission_requests(user_id, chat_id, status, created_at, id);

create table agent_execution_checkpoints (
  execution_id uuid primary key references agent_executions(id) on delete cascade,
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  schema_version integer not null,
  revision bigint not null,
  phase text not null,
  context_json jsonb not null,
  batch_json jsonb not null,
  next_ordinal integer not null default 0,
  base_state_row_version bigint not null,
  compatibility_key text not null,
  lease_token uuid,
  lease_expires_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  row_version bigint not null default 0,
  constraint agent_execution_checkpoints_phase_check
    check (phase in (
      'batch_ready',
      'waiting_permission',
      'resume_queued',
      'resume_claimed',
      'graph_resuming'
    )),
  constraint agent_execution_checkpoints_context_object_check
    check (jsonb_typeof(context_json) = 'object'),
  constraint agent_execution_checkpoints_batch_object_check
    check (jsonb_typeof(batch_json) = 'object'),
  constraint agent_execution_checkpoints_next_ordinal_check
    check (next_ordinal >= 0)
);

create index agent_execution_checkpoints_recovery_idx
on agent_execution_checkpoints(phase, lease_expires_at, updated_at);

create table agent_tool_invocations (
  execution_id uuid not null references agent_executions(id) on delete cascade,
  invocation_id uuid not null,
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  batch_revision bigint not null,
  ordinal integer not null,
  provider_call_id text,
  tool_name text not null,
  arguments_json jsonb not null,
  arguments_hash text not null,
  tool_definition_hash text not null,
  phase text not null,
  result_message_json jsonb,
  error_code text,
  started_at timestamptz,
  finished_at timestamptz,
  updated_at timestamptz not null default now(),
  primary key(execution_id, invocation_id),
  constraint agent_tool_invocations_phase_check
    check (phase in (
      'planned',
      'invoking',
      'waiting_permission',
      'resume_claimed',
      'executing',
      'result_stored',
      'failed'
    )),
  constraint agent_tool_invocations_arguments_object_check
    check (jsonb_typeof(arguments_json) = 'object'),
  constraint agent_tool_invocations_result_shape_check
    check (result_message_json is null or jsonb_typeof(result_message_json) = 'object'),
  unique(execution_id, batch_revision, ordinal)
);

create index agent_tool_invocations_recovery_idx
on agent_tool_invocations(execution_id, phase, ordinal);

alter table permission_requests
add constraint permission_requests_invocation_fk
foreign key(execution_id, invocation_id)
references agent_tool_invocations(execution_id, invocation_id)
on delete cascade;
