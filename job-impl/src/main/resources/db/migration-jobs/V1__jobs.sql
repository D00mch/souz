create table jobs (
    id uuid primary key,
    user_id text not null,
    title text not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    run_at timestamptz,
    cron text,
    time_zone text,
    scheduled_at timestamptz not null,
    available_at timestamptz,
    status text not null check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    attempts integer not null default 0 check (attempts between 0 and 3),
    created_at timestamptz not null default clock_timestamp(),
    last_finished_at timestamptz,
    last_error text,
    lease_token uuid,
    lease_until timestamptz,
    check ((cron is null and time_zone is null) or (cron is not null and time_zone is not null and run_at is null)),
    check ((status = 'RUNNING' and lease_token is not null and lease_until is not null)
        or (status <> 'RUNNING' and lease_token is null and lease_until is null))
);

create index jobs_owner_idx on jobs (user_id, created_at desc, id desc);
create index jobs_available_idx on jobs (available_at, id) where status = 'PENDING';
create index jobs_expired_idx on jobs (lease_until) where status = 'RUNNING';
