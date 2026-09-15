create table vk_bot_bindings (
  id uuid primary key,
  user_id text not null,
  chat_id uuid not null,
  group_token_encrypted text not null,
  group_token_hash text not null,
  link_secret_hash text,
  vk_group_id bigint not null,
  vk_group_name text,
  last_ts text,
  enabled boolean not null default true,
  vk_user_id bigint,
  vk_peer_id bigint,
  vk_first_name text,
  vk_last_name text,
  linked_at timestamptz,
  poller_owner text,
  poller_lease_until timestamptz,
  last_error text,
  last_error_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint vk_bot_bindings_chat_id_key unique (chat_id),
  constraint vk_bot_bindings_group_token_hash_key unique (group_token_hash)
);

create index vk_bot_bindings_enabled_idx
on vk_bot_bindings(enabled);

create index vk_bot_bindings_user_chat_idx
on vk_bot_bindings(user_id, chat_id);

create index vk_bot_bindings_enabled_lease_idx
on vk_bot_bindings(enabled, poller_lease_until);
