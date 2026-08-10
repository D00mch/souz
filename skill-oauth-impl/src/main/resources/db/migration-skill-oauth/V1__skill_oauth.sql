create table skill_oauth_credentials (
    user_id text not null,
    provider text not null,
    access_token_encrypted text not null,
    refresh_token_encrypted text,
    granted_scopes text not null default '',
    expires_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (user_id, provider)
);

create table skill_oauth_pending_states (
    state text primary key,
    user_id text not null,
    skill_id text not null,
    provider text not null,
    requested_scopes text not null default '',
    expires_at timestamptz not null
);

create index skill_oauth_pending_states_expires_at_idx on skill_oauth_pending_states (expires_at);
create unique index skill_oauth_pending_states_user_provider_idx on skill_oauth_pending_states (user_id, provider);
