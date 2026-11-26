ALTER TABLE document DROP CONSTRAINT document_knowledge_base_id_content_hash_key;

CREATE UNIQUE INDEX uq_document_active_content_hash
    ON document (knowledge_base_id, content_hash)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN document.content_hash IS '用于同库未删除文档的内容去重。';
