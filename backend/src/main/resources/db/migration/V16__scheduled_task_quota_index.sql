create index agent_executions_scheduled_daily_idx on agent_executions(user_id, started_at)
    where metadata->>'scheduledTask' = 'true';
