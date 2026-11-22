CREATE TABLE chat_conversation (
    id BIGINT PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES app_user(id),
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_base(id),
    title VARCHAR(200) NOT NULL DEFAULT '新会话',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chat_conversation_owner_updated
    ON chat_conversation (owner_user_id, updated_at DESC, id DESC);
CREATE INDEX idx_chat_conversation_knowledge_base ON chat_conversation (knowledge_base_id);

CREATE TABLE chat_message (
    id BIGINT PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversation(id),
    turn_id UUID NOT NULL,
    role TEXT NOT NULL,
    content TEXT,
    status TEXT,
    error_code VARCHAR(64),
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT chk_chat_message_role_status CHECK (
        (role = 'USER' AND status IS NULL)
        OR (role = 'ASSISTANT' AND status IS NOT NULL AND status IN (
            'PROCESSING', 'CANCEL_REQUESTED', 'ANSWERED', 'INSUFFICIENT_EVIDENCE', 'FAILED', 'CANCELLED'
        ))
    ),
    CONSTRAINT chk_chat_message_citations_array CHECK (jsonb_typeof(citations) = 'array'),
    CONSTRAINT uq_chat_message_turn_role UNIQUE (conversation_id, turn_id, role)
);

CREATE INDEX idx_chat_message_conversation_created ON chat_message (conversation_id, created_at, id);

-- 取消请求尚未完成时，仍占用当前会话的活跃消息名额。
CREATE UNIQUE INDEX uq_chat_message_active_assistant ON chat_message (conversation_id)
    WHERE role = 'ASSISTANT' AND status IN ('PROCESSING', 'CANCEL_REQUESTED');
