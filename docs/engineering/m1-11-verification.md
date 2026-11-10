# M1-11 首次索引发布验收

## 范围与流程

分支 feature/m1-ingestion-indexing，基线 1bab6402afee2746a87c59a924707008b4917efc；实施前工作区干净。

IndexPublishService.publish(ClaimedTask) 使用普通 @Transactional（默认 REQUIRED）管理整个发布事务。Repository 无事务传播配置。发布只依据 PG 已提交的 VERIFIED，不调用 ES、MinIO 或模型。

复用 EmbeddingMapper.lockDocument、lockVersion 和 TaskMapper.lockTask，严格按 Document → IndexVersion → ImportTask 获取行锁，锁内检查存在性、三者归属和 claim_epoch。领取逻辑保持只锁 Task。

## 条件更新及原子性

依次执行 Mapper XML 中三条 SQL，每条必须恰好更新一行，否则抛出脱敏运行时异常并回滚：

1. Document：ID 匹配且 active_version_id IS NULL，设置目标版本。
2. IndexVersion：ID、document_id 匹配，VERIFIED 且 published_at IS NULL，更新 PUBLISHED 和数据库 statement_timestamp()。Service 同时要求 expected_chunk_count > 0。
3. ImportTask：ID、document_id、index_version_id、RUNNING、lease_owner、claim_epoch 全部匹配，且 lease_until > statement_timestamp()；更新 SUCCEEDED，清理租约、重试时间和错误，使用数据库时间更新 updated_at。

租约有效性最终由第三条 SQL 判断，不使用 Java 时钟；即使前两条已执行，第三条失败也全部回滚。没有自动修复或版本切换。

## 幂等

锁内归属和 claim_epoch 校验后，仅当文档指向当前版本、版本 PUBLISHED 且发布时间非空、任务 SUCCEEDED 时返回成功，不执行更新，不要求已清理的原租约有效。其他发布事实不一致、其他版本指针和代际冲突均拒绝。

## 测试设计

IndexPublishIT 使用仓库固定 digest 的 PostgreSQL Testcontainers 镜像及禁止拉取策略，独立容器数据库在结束后销毁，测试触发器明确删除。

一个 JUnit 方法覆盖正常三实体提交、发布时间及租约/诊断字段清理、非 VERIFIED、其他版本指针、版本归属冲突、任务两种关联冲突、非 RUNNING、owner/epoch 冲突、过期租约、三种发布事实不一致、重复调用不写入、已发布后代际冲突与指针覆盖拒绝。失败场景比较三实体完整 JSON，确认没有部分写入。真实 PG BEFORE UPDATE Trigger 返回 NULL，令第三条更新零行，确认前两条回滚，任务不变。

测试实际断言 PostgreSQL 17.6、pgvector 0.8.0；Flyway V1～V5 执行成功。额外的 VERIFIED 零 Chunk 数据准备被既有数据库 CHECK 拒绝，移除此无效准备后重跑发布测试成功；未修改数据库约束。

## 实际验证

- mvn spotless:apply spotless:check -DskipTests test-compile：成功。
- mvn -Dtest=IndexPublishIT test：1 项成功，零失败/错误/跳过。
- 首轮组合回归中 ImportTaskIT 1 项、IndexVerificationIT 2 项、PgIntegrityIT 1 项均成功；发布测试因上述无效数据准备报错，修正后单独重跑通过。
- 默认 mvn test：126 项全部通过，零失败/错误/跳过，包含 ImportTaskTests 回归。
- git diff --check、git diff --cached --check：通过；完整 diff 已审阅，仅本次七个文件变更。日志位于 target/m111-build.log、m111-target.log、m111-publish.log、m111-default.log。
- 未执行供应商模型请求、真实 MinIO、生产 Worker E2E、多 Worker 并发和性能测试。

## 修改文件

- ingestion/publish/IndexPublishService.java：事务、业务前置条件和幂等。
- ingestion/publish/IndexPublishRepository.java：复用锁和更新行数检查。
- ingestion/publish/IndexPublishMapper.java、resources/mapper/ingestion/IndexPublishMapper.xml：发布条件更新。
- IndexPublishIT.java：真实数据库测试。
- pom.xml：注册 Failsafe 发布 IT。
- 本验收文档。

## 边界与未关闭问题

M1-11 只完成发布事务，没有实现完整 Worker；没有新增 Controller、启用 Scheduler、恢复任务或进入 M1-12。无已知发布实现阻塞问题。架构文档 Boot 3.x 与当前 pom 4.1.2 的既有差异保持不变；不修改冻结文档、迁移、索引协议或验证算法。
