ALTER TABLE knowledge_base
    ADD COLUMN updated_at TIMESTAMPTZ,
    ADD COLUMN deleted_at TIMESTAMPTZ;

UPDATE knowledge_base SET updated_at = created_at;

ALTER TABLE knowledge_base
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE document
    ADD COLUMN deleted_at TIMESTAMPTZ;
