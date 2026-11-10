CREATE TABLE knowledge_base (
 id BIGINT PRIMARY KEY, name TEXT NOT NULL, description TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE document (
 id BIGINT PRIMARY KEY, knowledge_base_id BIGINT NOT NULL, filename TEXT NOT NULL,
 content_hash TEXT NOT NULL, object_key TEXT NOT NULL, active_version_id BIGINT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (knowledge_base_id, content_hash)
);
CREATE TABLE index_version (
 id BIGINT PRIMARY KEY, document_id BIGINT NOT NULL,
 status TEXT NOT NULL DEFAULT 'BUILDING' CHECK (status IN ('BUILDING','VERIFIED','PUBLISHED','FAILED')),
 expected_chunk_count INTEGER NOT NULL DEFAULT 0 CHECK (expected_chunk_count >= 0),
 embedding_model TEXT NOT NULL DEFAULT 'text-embedding-v4' CHECK (embedding_model = 'text-embedding-v4'),
 embedding_dimension INTEGER NOT NULL DEFAULT 1024 CHECK (embedding_dimension = 1024),
 chunking_config TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, published_at TIMESTAMPTZ,
 CHECK (status NOT IN ('VERIFIED','PUBLISHED') OR expected_chunk_count > 0)
);
CREATE TABLE document_chunk (
 id BIGINT PRIMARY KEY, knowledge_base_id BIGINT NOT NULL, document_id BIGINT NOT NULL,
 index_version_id BIGINT NOT NULL, chunk_order INTEGER NOT NULL CHECK (chunk_order >= 0),
 content TEXT NOT NULL, source_start INTEGER NOT NULL CHECK (source_start >= 0),
 source_end INTEGER NOT NULL, heading_path TEXT, embedding VECTOR(1024),
 CHECK (source_end > source_start), UNIQUE (index_version_id, chunk_order)
);
CREATE TABLE import_task (
 id BIGINT PRIMARY KEY, document_id BIGINT NOT NULL, index_version_id BIGINT NOT NULL,
 status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
 attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
 claim_epoch BIGINT NOT NULL DEFAULT 0 CHECK (claim_epoch >= 0),
 lease_owner TEXT, lease_until TIMESTAMPTZ, next_retry_at TIMESTAMPTZ,
 last_error_code TEXT, last_error_message TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_index_version_document ON index_version(document_id);
CREATE INDEX idx_chunk_document ON document_chunk(document_id);
CREATE INDEX idx_chunk_knowledge_base ON document_chunk(knowledge_base_id);
CREATE INDEX idx_task_document ON import_task(document_id);
CREATE INDEX idx_task_version ON import_task(index_version_id);
CREATE INDEX idx_task_retry ON import_task(status, next_retry_at);
CREATE INDEX idx_task_lease ON import_task(status, lease_until);
COMMENT ON COLUMN document_chunk.source_start IS '原始文本 Unicode 码点偏移，含起点';
COMMENT ON COLUMN document_chunk.source_end IS '原始文本 Unicode 码点偏移，不含终点';
COMMENT ON COLUMN import_task.claim_epoch IS '领取代际；后续领取事务递增并在条件更新中校验';
