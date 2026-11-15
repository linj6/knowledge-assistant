CREATE TABLE app_user (
    id BIGINT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE app_user IS '应用用户；不预置用户或密码。';
COMMENT ON COLUMN app_user.id IS '应用生成的雪花主键，数据库不自增。';
COMMENT ON COLUMN app_user.username IS '唯一用户名，大小写敏感。';
COMMENT ON COLUMN app_user.password_hash IS '密码散列，不保存明文密码。';
COMMENT ON COLUMN app_user.status IS '用户状态：ACTIVE 启用，DISABLED 禁用。';
COMMENT ON COLUMN app_user.created_at IS '用户创建时刻。';
ALTER TABLE knowledge_base ADD COLUMN owner_user_id BIGINT NULL;
COMMENT ON COLUMN knowledge_base.owner_user_id IS '所有者用户标识；历史数据暂为空，受控绑定后由 AUTH-04 完成权限闭环。';
CREATE INDEX idx_knowledge_base_owner_user ON knowledge_base(owner_user_id);
