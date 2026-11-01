create table skill_plugin_artifacts (
  plugin_id text not null,
  version text not null,
  digest text not null check (digest ~ '^[a-f0-9]{64}$'),
  artifact bytea not null check (octet_length(artifact) between 1 and 67108864),
  created_at timestamptz not null default now(),
  primary key (plugin_id, version),
  unique (plugin_id, digest)
);

create table skill_plugin_desired (
  plugin_id text primary key,
  digest text not null,
  enabled boolean not null,
  revision bigint not null check (revision > 0),
  updated_at timestamptz not null default now(),
  foreign key (plugin_id, digest) references skill_plugin_artifacts(plugin_id, digest)
);

create table skill_plugin_state (
  plugin_id text not null,
  owner_id text not null references users(id) on delete cascade,
  state_key text not null check (octet_length(state_key) between 1 and 256),
  value jsonb not null check (octet_length(value::text) <= 65536),
  data_schema_version integer not null check (data_schema_version > 0),
  revision bigint not null check (revision > 0),
  primary key (plugin_id, owner_id, state_key)
);
