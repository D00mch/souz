create table if not exists memory_entity (
    id text primary key,
    scope_type text not null,
    scope_id text not null,
    entity_type text not null,
    canonical_name text not null,
    display_name text not null,
    normalized_key text not null,
    status text not null,
    created_at text not null,
    updated_at text not null
);

create unique index if not exists memory_entity_normalized_key_idx
on memory_entity(normalized_key);

create table if not exists memory_entity_alias (
    id text primary key,
    entity_id text not null references memory_entity(id) on delete cascade,
    alias text not null,
    normalized_alias text not null,
    source text,
    created_at text not null
);

create index if not exists memory_entity_alias_normalized_alias_idx
on memory_entity_alias(normalized_alias);

create unique index if not exists memory_entity_alias_unique_idx
on memory_entity_alias(entity_id, normalized_alias);

create table if not exists memory_episode (
    id text primary key,
    scope_type text not null,
    scope_id text not null,
    title text not null,
    summary text not null,
    status text not null,
    started_at text not null,
    ended_at text,
    last_touched_at text not null,
    next_action text,
    importance real
);

create index if not exists memory_episode_scope_last_touched_idx
on memory_episode(scope_type, scope_id, last_touched_at desc);

create table if not exists memory_evidence (
    id text primary key,
    scope_type text not null,
    scope_id text not null,
    evidence_type text not null,
    source_ref text not null,
    source_hash text,
    content_excerpt text,
    content_json text,
    created_at text not null
);

create table if not exists memory_fact (
    id text primary key,
    scope_type text not null,
    scope_id text not null,
    subject_entity_id text not null references memory_entity(id) on delete cascade,
    predicate text not null,
    object_kind text not null,
    object_entity_id text references memory_entity(id) on delete set null,
    object_value_text text,
    object_value_json text,
    slot_key text,
    confidence real not null,
    status text not null,
    reason_to_store text not null,
    created_at text not null,
    valid_from text not null,
    invalidated_at text,
    invalidated_by_fact_id text references memory_fact(id) on delete set null,
    origin_episode_id text references memory_episode(id) on delete set null,
    writer_version text
);

create index if not exists memory_fact_scope_status_idx
on memory_fact(scope_type, scope_id, status);

create index if not exists memory_fact_slot_status_idx
on memory_fact(slot_key, status);

create unique index if not exists memory_fact_active_slot_unique_idx
on memory_fact(slot_key)
where slot_key is not null and status = 'ACTIVE';

create index if not exists memory_fact_subject_predicate_status_idx
on memory_fact(subject_entity_id, predicate, status);

create table if not exists memory_fact_evidence (
    id text primary key,
    fact_id text not null references memory_fact(id) on delete cascade,
    evidence_id text not null references memory_evidence(id) on delete cascade,
    support_type text not null,
    weight real not null,
    created_at text not null
);

create index if not exists memory_fact_evidence_fact_idx
on memory_fact_evidence(fact_id);

create index if not exists memory_fact_evidence_evidence_idx
on memory_fact_evidence(evidence_id);

create table if not exists memory_embedding_doc (
    id text primary key,
    doc_type text not null,
    source_record_type text not null,
    source_record_id text not null,
    scope_type text not null,
    scope_id text not null,
    text text not null,
    status text not null,
    embedding_model_fingerprint text,
    created_at text not null,
    updated_at text not null
);

create index if not exists memory_embedding_doc_status_model_idx
on memory_embedding_doc(status, embedding_model_fingerprint);

create table if not exists memory_write_attempt (
    id text primary key,
    scope_type text not null,
    scope_id text not null,
    turn_ref text,
    trigger_type text not null,
    input_excerpt text,
    candidates_json text not null,
    accepted_count integer not null,
    rejected_count integer not null,
    rejection_reasons_json text,
    created_at text not null
);

create table if not exists memory_injection_log (
    id text primary key,
    scope_type text not null,
    scope_id text not null,
    turn_ref text,
    query_excerpt text,
    selected_record_ids_json text not null,
    rendered_packet text not null,
    estimated_tokens integer not null,
    created_at text not null
);
