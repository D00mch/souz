alter table stored_attachments add column object_key text;

update stored_attachments
set object_key = 'files/' || encode(sha256(convert_to(user_id, 'UTF8')), 'hex') || '/' || id::text;

alter table stored_attachments alter column object_key set not null;
alter table stored_attachments add unique (object_key);
