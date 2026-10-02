alter table chats add constraint chats_user_id_id_key unique (user_id, id);

create table conversation_knowledge (
  id uuid primary key,
  user_id text not null,
  chat_id uuid not null,
  record_json text not null check (octet_length(record_json) <= 8388608),
  foreign key (user_id, chat_id) references chats(user_id, id) on delete cascade
);

create index conversation_knowledge_owner_idx on conversation_knowledge(user_id, chat_id);
