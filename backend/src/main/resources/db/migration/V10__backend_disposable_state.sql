create table skill_bundles (
  bundle_hash text primary key
    check (bundle_hash ~ '^[0-9a-f]{64}$'),
  manifest_json jsonb not null
    check (jsonb_typeof(manifest_json) = 'object'),
  file_count integer not null
    check (file_count between 1 and 64),
  total_bytes integer not null
    check (total_bytes between 1 and 524288),
  created_at timestamptz not null default now()
);

create table skill_bundle_files (
  bundle_hash text not null references skill_bundles(bundle_hash) on delete cascade,
  normalized_path text not null,
  content bytea not null,
  primary key(bundle_hash, normalized_path),
  constraint skill_bundle_files_normalized_path_check check (
    normalized_path <> ''
    and normalized_path = btrim(normalized_path)
    and normalized_path !~ '(^|/)\.\.?(/|$)'
    and normalized_path !~ '(^/|/$|\\|//|[[:cntrl:]])'
  ),
  constraint skill_bundle_files_content_size_check
    check (octet_length(content) <= 131072)
);

create table user_skill_registrations (
  user_id text not null references users(id) on delete cascade,
  skill_id text not null
    check (skill_id ~ '^[A-Za-z0-9._-]+$' and skill_id not in ('.', '..')),
  bundle_hash text not null references skill_bundles(bundle_hash),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key(user_id, skill_id)
);

create index user_skill_registrations_bundle_hash_idx
on user_skill_registrations(bundle_hash);

create table skill_validations (
  user_id text not null references users(id) on delete cascade,
  skill_id text not null
    check (skill_id ~ '^[A-Za-z0-9._-]+$' and skill_id not in ('.', '..')),
  bundle_hash text not null
    check (bundle_hash ~ '^[0-9a-f]{64}$'),
  policy_version text not null,
  approved boolean not null,
  findings_json jsonb not null default '[]'
    check (jsonb_typeof(findings_json) = 'array'),
  created_at timestamptz not null,
  updated_at timestamptz not null default now(),
  primary key(user_id, skill_id, bundle_hash, policy_version),
  constraint skill_validations_policy_version_check check (
    policy_version ~ '^[A-Za-z0-9._/-]+$'
    and policy_version !~ '(^|/)\.\.?(/|$)'
    and policy_version !~ '(^/|/$|//)'
  )
);

create index skill_validations_bundle_hash_idx
on skill_validations(bundle_hash);

create unique index chats_user_id_id_idx
on chats(user_id, id);

create table conversation_knowledge (
  user_id text not null,
  chat_id uuid not null,
  id uuid not null,
  source_tool bytea not null check (
    octet_length(source_tool) > 0
    and octet_length(source_tool) % 2 = 0
  ),
  original_length integer not null check (original_length >= 0),
  complete_content bytea,
  head_content bytea,
  tail_content bytea,
  created_at timestamptz not null default now(),
  primary key(user_id, chat_id, id),
  constraint conversation_knowledge_chat_fk
    foreign key(user_id, chat_id)
    references chats(user_id, id)
    on delete cascade,
  constraint conversation_knowledge_content_shape check (
    (
      complete_content is not null
      and head_content is null
      and tail_content is null
      and octet_length(complete_content) <= 2097152
      and octet_length(complete_content) % 2 = 0
      and octet_length(complete_content)::bigint = original_length::bigint * 2
    )
    or
    (
      complete_content is null
      and head_content is not null
      and tail_content is not null
      and octet_length(head_content) > 0
      and octet_length(tail_content) > 0
      and octet_length(head_content) <= 1048576
      and octet_length(tail_content) <= 1048576
      and octet_length(head_content) % 2 = 0
      and octet_length(tail_content) % 2 = 0
      and (
        octet_length(head_content)::bigint + octet_length(tail_content)::bigint
      ) / 2 < original_length::bigint
    )
  )
);

create table backend_codex_oauth_credentials (
  singleton boolean primary key default true,
  encrypted_payload bytea not null,
  version bigint not null check (version >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint backend_codex_oauth_credentials_singleton check (singleton),
  constraint backend_codex_oauth_credentials_encrypted_payload_check check (
    octet_length(encrypted_payload) > 7
    and substring(encrypted_payload from 1 for 7) = convert_to('enc:v1:', 'UTF8')
  )
);

create table backend_codex_oauth_bootstrap (
  singleton boolean primary key default true,
  completed_at timestamptz not null default now(),
  constraint backend_codex_oauth_bootstrap_singleton check (singleton)
);
