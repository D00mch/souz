create table scheduled_hook_receipts (
  execution_id uuid primary key references agent_executions(id) on delete cascade,
  occurrence_key text not null unique,
  admitted_at timestamptz not null default clock_timestamp()
);
