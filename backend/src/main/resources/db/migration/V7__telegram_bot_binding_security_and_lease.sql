alter table telegram_bot_bindings
    add column if not exists bot_token_encrypted text,
    add column if not exists bot_username text,
    add column if not exists bot_first_name text,
    add column if not exists telegram_user_id bigint,
    add column if not exists telegram_chat_id bigint,
    add column if not exists telegram_username text,
    add column if not exists telegram_first_name text,
    add column if not exists telegram_last_name text,
    add column if not exists linked_at timestamptz,
    add column if not exists poller_owner text,
    add column if not exists poller_lease_until timestamptz;

update telegram_bot_bindings
set bot_token_encrypted = bot_token
where bot_token_encrypted is null;

alter table telegram_bot_bindings
    alter column bot_token_encrypted set not null;

alter table telegram_bot_bindings
    drop column if exists bot_token;

create index if not exists telegram_bot_bindings_enabled_lease_idx
    on telegram_bot_bindings(enabled, poller_lease_until);
