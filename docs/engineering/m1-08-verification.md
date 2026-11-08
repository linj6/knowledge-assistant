# M1-08 导入状态机与后台领取验证

## 基线与范围

分支 `feature/m1-ingestion-indexing`，起点 `4c11b7f8ff7aa98974891a8edeb53406eba71e3e`，初始工作区干净。沿用 Java 21、Boot 4.1.2 和现有依赖，未修改冻结规范或 V1～V5。架构文档 Boot 3.x 与实际 pom 的已知差异保持不动。现有表字段足够，不新增迁移。

上传仍在单个 PG 事务创建 Document、BUILDING IndexVersion、PENDING ImportTask，初始 attemptCount/claimEpoch 为零。查询 API 和四种外部状态映射不变。ChunkService 的初始化事务、Embedding 的事务外调用及批次写入事务、ES 的禁止外层事务边界均未改动，也没有把它们接入 Worker。

## 状态和失败协议

本阶段普通 Java 状态机仅允许 PENDING → RUNNING、RUNNING → FAILED。其他组合（包括 SUCCEEDED 的所有出边）拒绝。数据库 SQL 同时约束源状态，不能仅依赖内存判断。没有成功更新 SQL；RUNNING → SUCCEEDED 只允许未来 M1-11 的真实验证及发布事务执行。FAILED 重试和过期 RUNNING 接管均未实现。

TaskFailure 使用固定安全码：NOT_FOUND、NOT_CLAIMABLE、OWNERSHIP_INVALID、ALREADY_CLAIMED、TRANSITION_CONFLICT、AUTHORITY_EXPIRED。执行失败持久化 EXECUTION_FAILED 和固定中文消息；不保存原始异常、供应商正文、密钥或原文。按 ID 领取用于明确诊断，扫描遇到已锁任务直接跳过，空队列返回 Optional.empty。未来时间的 PENDING 不可执行；按 ID 条件更新失败返回转换冲突。

## SQL、事务和锁顺序

全部新业务 SQL 位于 [TaskMapper.xml](../../src/main/resources/mapper/ingestion/TaskMapper.xml)，Java Mapper 不含 SQL。领取由 Service 的 REQUIRES_NEW 短事务包围：

```sql
SELECT * FROM document_import_task
WHERE status='PENDING'
  AND (next_retry_at IS NULL OR next_retry_at <= statement_timestamp())
ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED;
```

锁定后普通快照查询核对 Document 存在、版本存在且属于该文档、文档未发布、版本 BUILDING 且未发布，再条件更新任务。更新、计数及代际递增在同一事务内，失败全部回滚。按 ID 诊断入口使用 FOR UPDATE，可能等待竞争者提交后返回 ALREADY_CLAIMED；后台扫描使用 SKIP LOCKED 不等待其他任务行。

**统一协议：涉及多实体的事务按 Document → IndexVersion → ImportTask 加锁；只涉及任务的领取和失败事务只锁 ImportTask，之后禁止获取 Document/IndexVersion 行锁。** 当前关联查询不带行锁，因而不存在“持有 Task 等待 Document”的反向锁边。多任务未来批处理也必须逐任务独立事务，不跨任务积累锁。M1-09/M1-11 必须沿用此协议；发布必须先检查 RUNNING 及有效执行权，不能发布 PENDING 任务。文档/版本归属创建后不可变，本次没有新增修改归属的业务入口；快照关联检查不能抵御协议外直接 SQL 修改归属，不宣称完整 fencing。

真实 PG 测试除锁住首个 Task 后从另一个连接领取下一任务外，还模拟先持有 Document/IndexVersion 锁、并发领取对应 Task 完成，然后继续锁 Task，验证没有反向等待。不是完整发布事务或压力测试。

## 执行权字段与调度

- leaseOwner：每个调度实例生成 UUID；Service 拒绝空白或超过128字符的身份。
- leaseUntil：PG statement_timestamp() + 120秒，固定冻结基线；updatedAt 同一语句使用数据库时间。
- attemptCount、claimEpoch：每次成功领取分别加一。ClaimedTask 是不可变 record，返回 taskId、documentId、indexVersionId、leaseOwner、claimEpoch。claimEpoch 只是未来 fencing 令牌，不代表外部写入已受保护。
- 失败条件同时核对 RUNNING、任务及文档/版本 ID、leaseOwner、claimEpoch 和未过期 leaseUntil；任何失效均不覆盖现状。

Spring Scheduling 注解已在当前 Boot 4.1.2 依赖下编译验证。`assistant.worker.enabled=false` 默认禁用，条件配置不注册调度 Bean；`scan-interval=5s` 固定延迟，`batch-size=1`，允许1～10。顺序处理，不创建任务线程池；每轮最多领取指定数量，空队列提前结束。异常捕获后正常返回，下轮仍可运行，不输出异常正文。没有启动时重置 RUNNING。

TaskWorker 独立接收 ClaimedTask，拒绝在外层事务内执行处理器。处理器返回后任务保持 RUNNING；异常时尝试条件写 FAILED。调度开启但没有 TaskProcessor Bean 时不领取。仓库不提供生产 TaskProcessor Bean，真实链路保持关闭，测试显式注入可控处理器。当前不要通过注册调用真实模型/ES的处理器绕过 M1-09。

## 实际验证

- `mvn spotless:apply spotless:check -DskipTests test-compile`：成功。
- `mvn '-Dtest=ImportTaskTests,ImportTaskIT' test`：首轮4项通过，0失败、0错误、0跳过；包含3个离线测试和1个真实 PG 组合测试。补充锁顺序和缺失关联检查后最终复测仍为4项通过，0失败、0错误、0跳过。
- 离线：遍历全部状态对、处理器返回不发布、异常只触发固定失败入口、无处理器不领取、某轮失败后仍可再次扫描。Mock 不用于证明数据库原子性。
- 真实 PG：使用既有固定 digest 的 pgvector 0.8.0/PG17本地镜像、禁止拉取，实际 PostgreSQL 17.6；V1～V5迁移成功。两个连接竞争单任务、20个任务并发领取不重复、SKIP LOCKED、空队列、状态排除、120秒租约/owner/计数/代际、非零计数递增、错误owner/epoch/过期租约拒绝、缺失任务、归属异常、触发器注入更新失败全字段回滚、新建应用上下文后发现持久 PENDING。默认启动无调度Bean，模型与ES禁用。没有调用供应商或启动 Compose 基础设施。

## 后续依赖及未验证事项

M1-09 负责续租、执行权协议和 PG/ES 迟到写入保护；M1-11 负责真实验证后的首次发布事务；M1-12 负责 FAILED 有限重试、退避、过期接管及清理。测试中新建上下文验证持久任务发现，不等同于 kill -9 崩溃恢复。未验证真实多进程部署、网络分区、长时间压力、完整发布/接管死锁竞态、真实调度定时器长时间运行、完整导入 E2E。当前处理器完成保持 RUNNING，后续接管尚未实现；不能用于生产自动导入。损坏关联的队首任务会使该轮失败并停止，不自动跳过或修复，需人工诊断，恢复策略留后续阶段。

最终 `git diff --check` 和暂存区检查通过；已审阅完整变更。未执行全量测试，按本次影响范围验证。
