alter table jobs add column continue_after_failure boolean not null default false;

create table job_occurrence_failures (
    job_id uuid not null references jobs(id) on delete cascade,
    scheduled_at timestamptz not null,
    attempts integer not null,
    finished_at timestamptz not null,
    error text not null,
    primary key (job_id, scheduled_at)
);
