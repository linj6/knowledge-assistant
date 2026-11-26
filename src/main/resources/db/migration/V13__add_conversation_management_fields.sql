ALTER TABLE chat_conversation
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN title_manually_edited BOOLEAN NOT NULL DEFAULT FALSE;
