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

## 2026-11-09 验收与复杂度复审

开始时位于 `refactor/m1-ingestion-readability`，工作区干净；以 `feature/m1-ingestion-indexing` 比较了全部六个文件。Java 21.0.10、Maven 3.9.16、Docker 29.4.0 可用，本地已有测试镜像。架构文档的 Boot 版本差异保持原样。

### 静态行为对比

逐段对比确认：上传校验、摘要、去重、配置快照、上传复制、PG 创建的顺序不变；上传补偿 catch、创建补偿 catch 和 staging finally 的覆盖范围不变。PG 提交异常仍先查询引用，查询失败保留 originals。Processor 的超时检查、阶段事件、异常映射及发布后不再检查超时均保留。发布事务的锁顺序、epoch 校验、完整发布事实判断和最终条件更新未变；重试间隔提取未改变随机退避调用次数。未修改 SQL、状态转换、API 或对象 Key。

### 复杂度决策

| 位置 | 原有复杂度 | 是否必要 | 处理方式 | 依据 |
| --- | --- | --- | --- | --- |
| DocumentImportService | 上传与 PG 异常分别补偿 | 是 | 保留 | 两种提交不确定窗口不同；PG 引用查询失败时不能删除 |
| 上传去重与唯一约束 | 两次裁决 | 是 | 保留 | 预查询减少上传，唯一约束裁决并发竞争，存在 TOCTOU |
| Processor | 任务、关联、状态检查 | 是 | 保留 | 表无关联外键，领取快照不能替代处理时检查 |
| TaskRepository | 重试延迟共同决策 | 是 | 保留 | 已提取 retryDelay，保留最大次数和不可重试判断 |
| TaskService | owner 校验与短事务 | 是 | 保留 | 入口约束及任务锁必须覆盖条件更新 |
| TaskScheduler / TaskWorker | 串行扫描、失败更新 | 是 | 保留 | synchronized 仅限进程内；失败 SQL 防止旧执行者覆盖状态 |
| IndexVerificationService | PG 快照、ES、PG 再确认 | 是 | 保留 | ES 网络调用不能置于 PG 长事务；计数不能证明集合相同 |
| IndexPublishService / SQL | 前置检查加最终执行权检查 | 是 | 保留 | 数据库时间可能跨过租约边界，最后更新失败须回滚整个事务 |
| 已重构的四个 Java 文件 | 格式不合规 | 否 | 简化 | 限定 ingestion 路径运行 Spotless，实际仅改变四个任务文件 |
| FAILED 版本领取、失配队首阻塞 | 领取与处理规则不一致 | 待单独处理 | 暂缓 | 修改涉及 SQL 与状态规则，超出本轮范围 |

没有发现值得扩大代码改动的可靠性冗余，不新增抽象、不删除校验。现有主流程提取已能表达上传、构建、核验和发布；本轮只修复格式并补齐验收证据。

### 验证记录

- 初次 `mvn spotless:check test` 在 Spotless 阶段失败，四个已重构文件格式不合规，尚未运行测试；限定路径格式化后修复。
- `mvn spotless:check test` 成功，主代码及测试代码编译成功，154 项测试，0 失败、0 错误、0 跳过。默认 Tests 中也包含真实 PG 测试。
- 真实集成测试使用已有 Testcontainers 配置，Embedding 使用本地 HTTP stub，没有调用真实供应商 API。
- 本次未运行在线模型测试、完整 Maven verify 或其他未选中的 IT；不声明穷尽所有并发交错，也不承诺多实例严格迟到写入 fencing。V1 仍限单 Worker 部署。
- `mvn "-Dit.test=IngestionPipelineIT,StorageImportIT,TaskRecoveryIT,ImportTaskIT,IndexPublishIT,IndexVerificationIT,PgIntegrityIT,EsIntegrityIT" failsafe:integration-test failsafe:verify` 成功：26 项，0 失败、0 错误、0 跳过。覆盖正常与重复上传、部分 Embedding/ES 失败重试、核验拒绝、失权发布拒绝、发布响应丢失恢复、存储复制补偿和提交结果不确定的引用保护。
- 完整 diff 已审阅，`git diff --check` 通过（仅提示 Markdown 换行将由 Git 转为 CRLF）。没有发现未解释的回归失败；未另建基线 checkout 执行测试，因此不声称完成基线双跑。
