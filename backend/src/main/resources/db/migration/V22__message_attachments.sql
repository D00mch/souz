alter table messages add column attachments jsonb not null default '[]';
