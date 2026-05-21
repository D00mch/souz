create table memory_entity (
  id text primary key,
  user_id text not null references users(id) on delete cascade,
  scope_type text not null,
  scope_id text not null,
  entity_type text not null,
  canonical_name text not null,
  display_name text not null,
  normalized_key text not null,
  status text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(user_id, normalized_key)
);

create index memory_entity_normalized_key_idx
on memory_entity(user_id, normalized_key);

create table memory_entity_alias (
  id text primary key,
  user_id text not null references users(id) on delete cascade,
  entity_id text not null references memory_entity(id) on delete cascade,
  alias text not null,
  normalized_alias text not null,
  source text,
  created_at timestamptz not null default now()
);

create index memory_entity_alias_normalized_alias_idx
on memory_entity_alias(user_id, normalized_alias);

create table memory_episode (
  id text primary key,
  user_id text not null references users(id) on delete cascade,
  scope_type text not null,
  scope_id text not null,
  title text not null,
  summary text not null,
  status text not null,
  started_at timestamptz not null default now(),
  ended_at timestamptz,
  last_touched_at timestamptz not null default now(),
  next_action text,
  importance double precision
);

create index memory_episode_scope_last_touched_idx
on memory_episode(user_id, scope_type, scope_id, last_touched_at desc);

create table memory_evidence (
  id text primary key,
  user_id text not null references users(id) on delete cascade,
  scope_type text not null,
  scope_id text not null,
  evidence_type text not null,
  source_ref text not null,
  source_hash text,
  content_excerpt text,
  content_json jsonb,
  created_at timestamptz not null default now()
);

create table memory_fact (
  id text primary key,
  user_id text not null references users(id) on delete cascade,
  scope_type text not null,
  scope_id text not null,
  subject_entity_id text not null references memory_entity(id) on delete cascade,
  predicate text not null,
  object_kind text not null,
  object_entity_id text references memory_entity(id) on delete set null,
  object_value_text text,
  object_value_json jsonb,
  slot_key text,
  confidence double precision not null,
  status text not null,
  reason_to_store text not null,
  created_at timestamptz not null default now(),
  valid_from timestamptz not null default now(),
  invalidated_at timestamptz,
  invalidated_by_fact_id text references memory_fact(id) on delete set null,
  origin_episode_id text references memory_episode(id) on delete set null,
  writer_version text
);

create index memory_fact_scope_status_idx
on memory_fact(user_id, scope_type, scope_id, status);

create index memory_fact_slot_status_idx
on memory_fact(user_id, slot_key, status);

create unique index memory_fact_active_slot_unique_idx
on memory_fact(user_id, slot_key)
where slot_key is not null and status = 'ACTIVE';

create index memory_fact_subject_predicate_status_idx
on memory_fact(user_id, subject_entity_id, predicate, status);

create table memory_fact_evidence (
  id text primary key,
  user_id text not null references users(id) on delete cascade,
  fact_id text not null references memory_fact(id) on delete cascade,
  evidence_id text not null references memory_evidence(id) on delete cascade,
  support_type text not null,
  weight double precision not null,
  created_at timestamptz not null default now()
);

create table memory_embedding_doc (
  id text primary key,
  user_id text not null references users(id) on delete cascade,
  doc_type text not null,
  source_record_type text not null,
  source_record_id text not null,
  scope_type text not null,
  scope_id text not null,
  text text not null,
  status text not null,
  embedding_model_fingerprint text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index memory_embedding_doc_status_model_idx
on memory_embedding_doc(user_id, status, embedding_model_fingerprint);

create table memory_write_attempt (
  id text primary key,
  user_id text not null references users(id) on delete cascade,
  scope_type text not null,
  scope_id text not null,
  turn_ref text,
  trigger_type text not null,
  input_excerpt text,
  candidates_json jsonb not null,
  accepted_count integer not null,
  rejected_count integer not null,
  rejection_reasons_json jsonb,
  created_at timestamptz not null default now()
);

create table memory_injection_log (
  id text primary key,
  user_id text not null references users(id) on delete cascade,
  scope_type text not null,
  scope_id text not null,
  turn_ref text,
  query_excerpt text,
  selected_record_ids_json jsonb not null,
  rendered_packet text not null,
  estimated_tokens integer not null,
  created_at timestamptz not null default now()
);
