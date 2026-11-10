# M1-09 单 Worker 执行边界与简化架构收尾

## 背景与验收结论

2026-11-08，按本次正式范围调整，将 V1 从完整分布式 Worker 方案收敛为单 Worker。起点 `7a48366b9566e1858f1aaf018c4b88f7f54bc869`，分支 `feature/m1-ingestion-indexing`，工作区初始干净，已检查最近提交及其文件变更。

本次简化验收门槛已达到：架构、任务计划、配置状态与现有执行边界一致。此结论仅针对架构收尾，不代表自动导入、超时恢复、跨进程互斥、fencing 或完整导入 E2E 通过。

## 最终模型和变更

- [Architecture Spec](../architecture/architecture.md)：第六节改为单 Worker 模型，同步故障验收门槛；保留双索引验证、PG原子发布和已发布版本不可变，未调整 RAG、Retrieval、Evaluation 设计。
- [Implementation Plan](../plans/implementation-plan.md)：修订 M1-09 名称和关键依赖，同步 M1-10～13 职责，移除完整分布式 fencing 强制门槛，保留常见故障与业务正确性验收。
- [配置基线](configuration-baseline.md)：区分已生效120秒 SQL 租约与未实施的历史30秒续租、10分钟执行时限，明确正式执行前必须关闭的问题。
- 本验证记录：说明部署约束、代码检查、验证范围与未实施能力。

保留 Spring Scheduling、PostgreSQL持久任务状态、稳定 Chunk ID、Chunk初始化/Embedding幂等、ES create-only Bulk与409内容比较、部分失败恢复要求。保留 claim_epoch、lease_owner、lease_until 和已有校验。取消 V1 完整 Epoch/Fencing、多实例导入、分布式锁及 ES External Versioning 建设；不引入 XXL-JOB、Redis/Redisson 或其他组件。

## 现有代码审阅

已阅读 TaskScheduler、TaskWorker、TaskService、TaskRepository、TaskMapper.xml、ClaimedTask、ImportTask 和 application.yml。未发现本次必须修改的独立明显业务错误，未修改 Java、SQL、配置、测试、依赖或迁移。原代码注释和 M1-08 记录中“后续 M1-09 fencing/续租”属于历史规划，本次正式决策取代该规划，保留历史证据，不据此继续建设分布式能力。

| 检查项 | 当前实现与限制（静态审阅） |
|---|---|
| 领取 | Service REQUIRES_NEW 短事务；PENDING 行锁/SKIP LOCKED；递增次数及代际 |
| 120秒租约 | SQL 使用数据库 statement_timestamp() 加120秒；不是任务超时或跨进程执行锁 |
| 正常返回 | TaskWorker 保持 RUNNING，没有成功更新入口 |
| 处理异常 | 条件更新 FAILED，固定脱敏码/消息，不保存异常正文 |
| 失败写入条件 | RUNNING、ID归属、owner、epoch及未过期lease；失效返回 AUTHORITY_EXPIRED |
| 续租及恢复 | 没有续租、过期RUNNING自动接管或启动重置 |
| 调度 | 默认关闭、固定延迟默认5秒、每轮默认1项（1～10）、顺序分发 |
| 生产处理 | 没有生产 TaskProcessor；无处理器时调度不领取，没有接入真实模型/ES链路 |

120秒后即使当前处理器抛异常也无法通过现有条件更新写 FAILED，可能滞留 RUNNING；处理器正常返回同样不代表成功发布。目前没有生产处理器，故该限制尚未影响正式自动导入，但必须在正式执行前由 M1-12 解决。30秒续租与10分钟任务上限未实施，不能称为已生效或验证通过。本次不通过删除安全校验或提前模拟 SUCCEEDED 来规避问题。

## 部署与恢复约束

只允许运行一个导入 Worker 实例，并顺序执行；手动执行入口不得与后台导入并行。发布、重启、恢复时必须确认原导入进程已停止，禁止滚动部署造成新旧 Worker 重叠。`enabled` 配置、随机 owner 和原子领取不保证跨进程互斥，也不保证租约过期后外部迟到写入被阻止。当前继续关闭调度，不注册生产处理器。

## 实际验证

- `mvn spotless:check -DskipTests test-compile`：BUILD SUCCESS；Spotless检查通过，主代码及测试编译检查通过（增量编译提示已是最新）。未执行测试。
- `git diff --check`：通过；已审阅完整文档 diff，提交前再次检查暂存区。
- 本次仅修改文档，未重跑离线测试或真实 PostgreSQL/ES/MinIO集成测试；M1-08历史测试不是本轮执行结果，不用于证明分布式可靠性。
- 未调用在线模型，未启动Docker基础设施，未修改Flyway V1～V5或依赖。

## 后续责任和未验证事项

M1-10：PG/ES完整性、完整ID集合与可搜索性验证。M1-11：验证后首次发布与幂等提交，PG为发布事实权威，已发布版本不可变。M1-12：基本重试、单任务超时、失败状态收敛、重启恢复与孤儿对象清理；正式Worker上线前解决120秒约束与长任务的关系，不要求建设自动续租框架。M1-13：单Worker完整导入E2E及常见故障验收。

上述后续能力本轮未实施或验证。多实例互斥、严格fencing、ES External Versioning、旧Worker迟到写入防护及极端竞态不属于V1验收范围，未来如支持多实例须另行设计。当前完整自动导入、超时恢复和E2E均不能标记PASS。本次到M1-09停止，不进入M1-10。
