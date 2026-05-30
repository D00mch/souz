alter table if exists choices rename to options;

alter index if exists choices_execution_idx rename to options_execution_idx;

alter index if exists choices_status_idx rename to options_status_idx;

update agent_executions
set status = 'waiting_option'
where status = 'waiting_choice';

update agent_events
set type = 'option.requested'
where type = 'choice.requested';

update agent_events
set type = 'option.answered'
where type = 'choice.answered';

drop index if exists agent_executions_one_active_per_chat_idx;

create unique index agent_executions_one_active_per_chat_idx
on agent_executions(user_id, chat_id)
where status in ('queued', 'running', 'waiting_option', 'cancelling');
