alter table user_settings
    add column personality_prompt text,
    add column personality_revision bigint not null default 1
        check (personality_revision between 1 and 9007199254740991);
