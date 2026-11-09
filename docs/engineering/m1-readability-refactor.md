# M1 导入链路可读性整理（不改变业务协议）

## 阅读顺序

1. `DocumentImportService.receive`：校验上传内容 → 去重 → 捕获配置快照 → 上传对象 → 在 PG 事务中创建任务。存储对象写入在 PG 事务之外。
2. `TaskScheduler.scan`：恢复其他执行者的过期任务 → 领取任务 → 启动 Worker。单进程 scan 串行不等于跨实例互斥。
3. `TaskRepository`：数据库任务行领取、领取轮次 `claimEpoch`、重试间隔和过期任务恢复。
4. `IngestionTaskProcessor.process`：检查任务与文档版本关系 → BUILDING 时构建 Chunk/Embedding/ES → 校验双索引 → 发布。VERIFIED 状态仍需重新校验。
5. `IndexVerificationService.verify`：PG 快照、ES 核验、PG 完成验证。调用外部 ES 时不持有 PG 事务。
6. `IndexPublishService.publish`：在单个 PG 事务中锁定文档、版本、任务，检查归属和执行权，幂等识别已发布事实，最后完成条件更新。

## 可靠性边界（本次不能为了精简而删除）

| 边界 | 为什么保留 |
| --- | --- |
| MinIO staging → originals → PG 记录 | PG 与 MinIO 没有共同事务；提交结果未知时必须检查 PG 引用才可删 originals |
| SHA-256 与文件合法性检查 | 防止不合法或与记录不符的原始内容参与索引 |
| 租约及 `claimEpoch` | 旧 Worker 的工作可能结束，但不代表旧 Worker 还能发布 |
| 发布事务内的归属、状态与条件更新 | 发布是对外可见性边界；不能靠前面的准备校验替代 |
| VERIFIED 再核验 | 防止恢复路径略过发布前的完整性检查 |
| ES 请求外的 PG 事务边界 | 避免长时间持有 PG 行锁 |
| 发布结果不确定后的重试确认 | 事务可能已经提交，不能直接视为失败 |
| 失败更新中的条件判断 | 已失去执行权的 Worker 不能覆盖其他 Worker 的状态 |

## 本次代码整理范围

- `IngestionTaskProcessor`：主流程命名为任务校验、构建索引、验证索引、发布；异常转换、超时位置、阶段日志及原有发布处理保持在原步骤。
- `IndexPublishService`：将原来的连续判断命名为领取参数检查、锁定实体检查、完整发布事实判定及发布条件检查；没有合并或减少事务内校验。
- `TaskRepository`：自动重试间隔的共同决策集中到 `retryDelay`，保留原先的调用顺序和 SQL。
- `TaskScheduler`：复用已领取对象，不再对 `Optional` 重复取值。
- `DocumentImportService`：区分输入校验、对象上传失败的目标对象清理、PG 创建失败时的引用检查与对象补偿；保留异常处理的嵌套范围和 finally 清理。

## 验收重点（待本地执行）

这轮是可读性重构，不宣称已经验证运行行为不变。合并前请执行：

1. `mvn spotless:check test`（如存在环境依赖，记录完整失败原因）。
2. 导入任务完整 E2E：上传、重复上传、切分、Embedding、ES、核验及发布成功。
3. MinIO 复制异常、PG 提交结果不确定、重复键竞争及临时对象清理。
4. 租约失效、`claimEpoch` 不匹配、两个 Worker 竞争、发布成功但响应丢失、VERIFIED 恢复。
5. 使用真实 PG/ES/MinIO 的相关集成测试，并核对数据库中的发布状态与双索引事实。

## 暂不修改的已知问题

- FAILED 版本仍可能被领取后由 Processor 判定失败：可读性重构不擅自调整任务选择条件。
- 失配队首任务可能阻断扫描：涉及 SQL 领取策略，另行设计并回归。
- 所有字段检查是否都有必要、是否存在重复防御，还需基于 SQL 约束、调用方及故障注入测试进行逐项审计。

**原则：先让编排可读，再用故障证据裁剪复杂度；不能以减少行数为由弱化一致性。**
