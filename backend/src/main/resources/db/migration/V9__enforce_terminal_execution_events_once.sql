create unique index if not exists agent_events_one_terminal_per_thread_idx
on agent_events(execution_id)
where type in ('execution.finished', 'execution.failed', 'execution.cancelled');
