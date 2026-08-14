create table skill_oauth_credentials (
    user_id text not null,
    provider text not null,
    access_token_encrypted text not null,
    refresh_token_encrypted text,
    granted_scopes text[] not null default '{}',
    expires_at timestamptz,
    generation bigint not null default 0,
    -- Optimistic-concurrency counter bumped on every successful write, independent of
    -- `generation` (which tracks authorization epochs, not individual writes). Two token
    -- refreshes racing for the same (user_id, provider) share one generation, so `generation`
    -- alone can't tell them apart; `revision` lets the second writer's CAS fail cleanly instead
    -- of silently clobbering the first writer's token material. See
    -- SkillOAuthCredentialRepository.upsert's doc comment.
    revision bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (user_id, provider)
);

create table skill_oauth_pending_states (
    state text primary key,
    user_id text not null,
    skill_id text not null,
    provider text not null,
    requested_scopes text[] not null default '{}',
    generation bigint not null default 0,
    expires_at timestamptz not null
);

create index skill_oauth_pending_states_expires_at_idx on skill_oauth_pending_states (expires_at);
create unique index skill_oauth_pending_states_user_provider_idx on skill_oauth_pending_states (user_id, provider);

-- Tracks the cumulative union of every scope ever requested for a (user_id, provider) pair,
-- independent of any single pending state's lifecycle (which is deleted the moment its callback
-- starts, well before a credential is saved). startAuthorization reads-and-bumps this row under a
-- `select ... for update` lock so a second, concurrent authorization for the same pair always
-- widens its own request to include everything requested so far, instead of only seeing whatever
-- happens to still be an unconsumed pending state. `generation` is a per-(user_id, provider)
-- monotonic counter bumped in the same locked read-modify-write; it lets credential writes reject
-- a stale/superseded save without having to trust the OAuth provider's own scope confirmation,
-- which is optional in its token response (RFC 6749) and therefore not always ground truth.
-- `updated_at` lets a long-abandoned request (its own authorize link long expired, never
-- completed) be treated as absent rather than folded into a much later, unrelated authorization.
create table skill_oauth_requested_scopes (
    user_id text not null,
    provider text not null,
    requested_scopes text[] not null default '{}',
    generation bigint not null default 0,
    updated_at timestamptz not null,
    primary key (user_id, provider)
);
