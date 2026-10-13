create table scheduled_task_daily_launches (
    user_id text not null references users(id) on delete cascade,
    launch_day date not null,
    launches integer not null check (launches > 0),
    primary key (user_id, launch_day)
);
