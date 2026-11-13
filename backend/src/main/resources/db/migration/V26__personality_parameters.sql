create table user_personalities (
    user_id text primary key references users(id) on delete cascade,
    parameters jsonb not null,
    revision bigint not null default 1 check (revision between 1 and 9007199254740991)
);

insert into user_personalities (user_id, parameters, revision)
select u.id, jsonb_build_object('voice', 'joy', 'appeal', 'no_official', 'style', 'informal',
                                'personality', coalesce(s.personality_prompt, '')), coalesce(s.personality_revision, 1)
from users u left join user_settings s on s.user_id = u.id;

alter table user_settings drop column personality_prompt, drop column personality_revision;
alter table chats add column your_name text;
