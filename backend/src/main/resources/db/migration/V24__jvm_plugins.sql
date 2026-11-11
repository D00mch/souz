create table jvm_plugins (
    plugin_id text primary key,
    default_digest text,
    enabled boolean not null default false,
    revision bigint not null default 0 check (revision >= 0)
);
create table jvm_plugin_versions (
    plugin_id text not null references jvm_plugins(plugin_id),
    version text not null,
    digest text not null check (digest ~ '^[a-f0-9]{64}$'),
    retired boolean not null default false,
    quarantined boolean not null default false,
    severe_incidents integer not null default 0 check (severe_incidents >= 0),
    primary key (plugin_id, version),
    unique (plugin_id, digest)
);
alter table jvm_plugins add foreign key (plugin_id, default_digest)
    references jvm_plugin_versions(plugin_id, digest) deferrable initially deferred;
create table jvm_plugin_calls (
    owner_id text not null references users(id) on delete cascade,
    plugin_id text not null references jvm_plugins(plugin_id),
    token uuid,
    digest text,
    lease_until timestamptz,
    failures integer not null default 0 check (failures >= 0),
    open_until timestamptz,
    recorded boolean not null default false,
    primary key (owner_id, plugin_id),
    foreign key (plugin_id, digest) references jvm_plugin_versions(plugin_id, digest),
    check ((token is null and digest is null and lease_until is null)
        or (token is not null and digest is not null and lease_until is not null))
);
create table jvm_plugin_state (
    owner_id text not null references users(id) on delete cascade,
    plugin_id text not null references jvm_plugins(plugin_id),
    state_key text collate "C" not null check (octet_length(state_key) between 1 and 512),
    value json not null check (octet_length(value::text) <= 1048576),
    primary key (owner_id, plugin_id, state_key)
);
create table jvm_plugin_incidents (
    plugin_id text not null,
    digest text not null,
    incident_id text not null,
    reason_code text not null,
    replica_id text not null,
    recorded_at timestamptz not null default clock_timestamp(),
    primary key (plugin_id, digest, incident_id),
    foreign key (plugin_id, digest) references jvm_plugin_versions(plugin_id, digest)
);
