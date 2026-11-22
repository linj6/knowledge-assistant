# M1-10A PostgreSQL 完整性验证

## 实施范围

新增 PgIntegrityService.verify(documentId, indexVersionId)，返回文档 ID、索引版本 ID 和实际 Chunk 数量。仅验证 PG，不改变任何业务状态，不涉及 ES、VERIFIED 转换、Worker 或 M1-11。

入口使用独立的 REPEATABLE_READ 只读事务，保证 Document、IndexVersion 和完整 Chunk 集合来自同一数据库快照；不加行锁。结果只证明该快照完整，不保证返回后的数据不会改变。

复用 EmbeddingRepository.read、ChunkRepository 和 VectorSchema：检查文档/版本存在、关联、BUILDING、空 published_at/active_version_id、正预期数量、实际数量、ID 唯一、零起连续顺序、Chunk 归属、记录 version 以及 1024 维有限 float 向量。知识库权威 ID 来自 Document，IndexVersion 不增加字段。入口补充 embedding 必须非空、正文非空白、来源起点非负且终点大于起点。沿用现有固定安全异常，不携带原文、向量或底层异常正文。未新增 SQL、迁移或修改生成流程。

## 修改文件

- src/main/java/com/lnjecit/knowledge/assistant/ingestion/verification/PgIntegrityService.java
- src/test/java/com/lnjecit/knowledge/assistant/PgIntegrityIT.java
- pom.xml：将新增 IT 纳入 Failsafe 清单。
- 本记录。

## 实际验证

- `mvn spotless:apply spotless:check -DskipTests test-compile`：最终 BUILD SUCCESS。
- `mvn '-Dtest=PgIntegrityIT' test`：最终 1 个真实集成测试通过，0 失败、错误、跳过。该测试内部覆盖正常完整数据、数量不足、缺失向量、顺序异常、知识库归属异常、非 BUILDING、非空 published_at、非空 active_version_id，并比较 Document、IndexVersion、Chunk、实际 ImportTask 记录验证前后数据。不是 8 个独立 JUnit 测试。
- 容器内断言 PostgreSQL 17.6、pgvector 0.8.0；使用仓库既有固定 digest 镜像和禁止拉取策略，Flyway V1～V5 成功执行。测试数据仅位于本次 Testcontainers 独立数据库，由容器生命周期清理。
- `git diff --check`、`git diff --cached --check`：通过，已审阅完整暂存 diff。

## 限制和边界

未运行全量测试、ES 或在线模型。未单独注入非法维度、NaN/Infinity：复用既有向量校验，真实 PG vector(1024) 本身限制此类存储；不将这些边界标为本轮测试通过。来源检查仅验证已有区间协议，不读取 MinIO 原文件来重建定位。

纯空白 Chunk 在本入口明确失败；此前切分与 Embedding 输入协议不一致的问题仍未修复，本轮没有改变切分策略。

没有修改 Architecture Spec、Flyway V1～V5、Document.active_version_id、published_at 或 ImportTask 状态，也不执行 BUILDING → VERIFIED。M1-10B 及发布逻辑均未实施。
