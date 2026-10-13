ALTER TABLE chats ADD COLUMN client_tools jsonb NOT NULL DEFAULT '[]'::jsonb;
