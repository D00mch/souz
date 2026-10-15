ALTER TABLE chats ADD COLUMN client_tools JSONB NOT NULL DEFAULT '[]'::jsonb;
