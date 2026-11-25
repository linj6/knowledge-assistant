ALTER TABLE chat_message
    ADD COLUMN memory_trace JSONB;

COMMENT ON COLUMN chat_message.memory_trace IS '实际注入回答生成请求的记忆数量和元数据';
