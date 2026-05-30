create table user_provider_keys (
  user_id text not null references users(id) on delete cascade,
  provider text not null,
  encrypted_api_key bytea not null,
  key_hint text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key(user_id, provider)
);
