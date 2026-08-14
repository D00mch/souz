create table backend_server_preferences (
  preference_key text primary key,
  encrypted_value bytea not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint backend_server_preferences_key_not_blank check (btrim(preference_key) <> '')
);
