create table telegram_bot_bindings (
  id uuid primary key,
  user_id text not null,
  chat_id uuid not null,
  bot_token text not null,
  bot_token_hash text not null,
  last_update_id bigint not null default 0,
  enabled boolean not null default true,
  last_error text,
  last_error_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint telegram_bot_bindings_chat_id_key unique (chat_id),
  constraint telegram_bot_bindings_bot_token_hash_key unique (bot_token_hash)
);

create index telegram_bot_bindings_enabled_idx
on telegram_bot_bindings(enabled);

create index telegram_bot_bindings_user_chat_idx
on telegram_bot_bindings(user_id, chat_id);
