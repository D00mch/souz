create table image_generation_attempts (
    user_id text not null,
    execution_id uuid not null references agent_executions(id) on delete cascade,
    fingerprint text not null,
    primary key (user_id, execution_id, fingerprint)
);
