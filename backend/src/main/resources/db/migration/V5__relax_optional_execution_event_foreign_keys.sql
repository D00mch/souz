alter table agent_executions
  drop constraint if exists agent_executions_user_message_id_fkey;

alter table agent_executions
  drop constraint if exists agent_executions_assistant_message_id_fkey;

alter table agent_events
  drop constraint if exists agent_events_execution_id_fkey;
