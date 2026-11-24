ALTER TABLE app_user
    ADD COLUMN memory_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN app_user.memory_enabled IS '长期记忆总开关';

CREATE TABLE user_memory (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    type VARCHAR(32) NOT NULL CHECK (type IN (
        'BACKGROUND', 'LEARNING_GOAL', 'ANSWER_PREFERENCE',
        'TECH_PREFERENCE', 'LEARNING_STATUS', 'OTHER'
    )),
    content VARCHAR(500) NOT NULL CHECK (content ~ '[^[:space:]]'),
    keywords VARCHAR(200),
    source_type VARCHAR(32) NOT NULL CHECK (source_type = 'MANUAL'),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_memory_content UNIQUE (user_id, type, content)
);

CREATE INDEX idx_user_memory_user_enabled ON user_memory (user_id, enabled);

COMMENT ON TABLE user_memory IS '用户显式保存的长期记忆';
COMMENT ON COLUMN user_memory.id IS '记忆雪花 ID';
COMMENT ON COLUMN user_memory.user_id IS '所属用户 ID';
COMMENT ON COLUMN user_memory.type IS '记忆类型';
COMMENT ON COLUMN user_memory.content IS '记忆正文';
COMMENT ON COLUMN user_memory.keywords IS '逗号分隔的主题关键词';
COMMENT ON COLUMN user_memory.source_type IS '记忆来源，当前为手动创建';
COMMENT ON COLUMN user_memory.enabled IS '单条记忆启用状态';
COMMENT ON COLUMN user_memory.created_at IS '创建时间';
COMMENT ON COLUMN user_memory.updated_at IS '更新时间';
