# AUTH-01 历史知识库绑定

V6 只修改结构，不执行业务绑定，不创建用户。owner_user_id 暂不设非空或外键，AUTH-04 前不代表已实施授权。

先在目标环境执行以下只读检查，并由负责人确认目标 ACTIVE 用户 ID、知识库 ID 清单和预期数量；发现悬空归属必须停止并调查，不自动修复。

```sql
SELECT id, username, status FROM app_user WHERE id = :target_user_id;
SELECT k.id, k.owner_user_id FROM knowledge_base k
LEFT JOIN app_user u ON u.id = k.owner_user_id
WHERE k.owner_user_id IS NOT NULL AND u.id IS NULL;
SELECT id, owner_user_id FROM knowledge_base WHERE id IN (:explicit_kb_ids);
```

本任务不执行绑定。获得用户明确授权后，将审核过的 ID 清单写入下面事务中的临时表；不要使用“所有 NULL 自动绑定”策略。此事务先检查再更新，失败整体回滚；提交前必须核对返回行和预期数量。数据库客户端应设置遇错停止，不应自动提交后续语句。

```sql
BEGIN;
LOCK TABLE app_user IN SHARE MODE;
LOCK TABLE knowledge_base IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE binding_target (user_id BIGINT NOT NULL) ON COMMIT DROP;
CREATE TEMP TABLE binding_kb (id BIGINT PRIMARY KEY) ON COMMIT DROP;
-- 人工填入已批准的一个目标用户及明确的知识库清单，禁止填入默认 ID。
-- INSERT INTO binding_target VALUES (...);
-- INSERT INTO binding_kb VALUES (...), (...);
DO $$
BEGIN
    IF (SELECT count(*) FROM binding_target) <> 1
       OR NOT EXISTS (SELECT 1 FROM app_user u JOIN binding_target t ON t.user_id=u.id WHERE u.status='ACTIVE')
       OR NOT EXISTS (SELECT 1 FROM binding_kb)
       OR EXISTS (SELECT 1 FROM knowledge_base k LEFT JOIN app_user u ON u.id=k.owner_user_id WHERE k.owner_user_id IS NOT NULL AND u.id IS NULL)
       OR EXISTS (SELECT 1 FROM binding_kb b LEFT JOIN knowledge_base k ON k.id=b.id WHERE k.id IS NULL OR k.owner_user_id IS NOT NULL)
    THEN
        RAISE EXCEPTION '绑定检查失败，未修改知识库';
    END IF;
    UPDATE knowledge_base k SET owner_user_id=t.user_id
    FROM binding_kb b CROSS JOIN binding_target t WHERE k.id=b.id;
    IF EXISTS (SELECT 1 FROM binding_kb b JOIN knowledge_base k ON k.id=b.id CROSS JOIN binding_target t WHERE k.owner_user_id IS DISTINCT FROM t.user_id) THEN
        RAISE EXCEPTION '绑定结果校验失败';
    END IF;
END $$;
SELECT k.id, k.owner_user_id FROM knowledge_base k JOIN binding_kb b ON b.id=k.id ORDER BY k.id;
-- 人工确认返回数量及归属后执行 COMMIT；不符合预期执行 ROLLBACK。
```

大表执行前评估锁等待并安排维护窗口。正式非空约束推迟到 AUTH-04 完成并验证全部历史归属后，以新迁移实施。

并发保护：上述表锁必须在检查前、同一事务内取得，并保持到 COMMIT 或 ROLLBACK。knowledge_base 的 SHARE ROW EXCLUSIVE 锁与其他绑定及普通 UPDATE 的 ROW EXCLUSIVE 锁冲突；等待前一事务提交后再检查归属，因此不会覆盖其已建立的归属。app_user 的 SHARE 锁阻止检查期间删除用户或修改状态。请使用 PostgreSQL 默认 READ COMMITTED 隔离级别。
