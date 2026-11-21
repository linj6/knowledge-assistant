-- 锁定表，避免在检查与设置约束之间写入未绑定记录。
LOCK TABLE knowledge_base IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM knowledge_base WHERE owner_user_id IS NULL) THEN
        RAISE EXCEPTION 'knowledge_base contains rows with NULL owner_user_id; bind owners before applying V7';
    END IF;
END $$;

ALTER TABLE knowledge_base ALTER COLUMN owner_user_id SET NOT NULL;

COMMENT ON COLUMN knowledge_base.owner_user_id IS '知识库所有者的应用用户标识，必填。';
