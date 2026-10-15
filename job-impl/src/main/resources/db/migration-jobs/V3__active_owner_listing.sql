create index jobs_active_owner_idx on jobs (user_id, created_at desc, id desc)
    where status in ('PENDING', 'RUNNING');
