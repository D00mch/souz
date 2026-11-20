create table attachment_execution_save_totals (
    execution_id uuid primary key references agent_executions(id) on delete cascade,
    save_count integer not null check (save_count > 0),
    size_bytes bigint not null check (size_bytes >= 0)
);

-- The owner window survives object cleanup and execution deletion.
create table attachment_save_charges (
    id uuid primary key,
    user_id text not null,
    size_bytes bigint not null check (size_bytes >= 0),
    admitted_at timestamptz not null
);

create index attachment_save_charges_owner_idx on attachment_save_charges(user_id, admitted_at);
create index attachment_save_charges_expiry_idx on attachment_save_charges(admitted_at, id);
