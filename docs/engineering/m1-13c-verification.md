# M1-13C 故障验收记录（未完成）

基线 feature/m1-ingestion-indexing / a0e889e5502731814dc2fa8ceca6b74b40dfecd6，开始时工作区干净。本文不构成 M1-13C 完整验收通过。

环境沿用 M1-13B Testcontainers：PostgreSQL 17.6 / pgvector 0.8.0 固定 digest；knowledge-assistant-elasticsearch:8.18.0-ik8.18.0；knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local。真实 Service、Repository、事务、对象读写；Embedding 使用本地 HTTP Stub，无 DashScope。

## 故障矩阵

| 用例 | 状态 | 证据和边界 |
| --- | --- | --- |
| C01 | PASS | IngestionPipelineIT 本地 HTTP Stub 持续 503 后恢复；真实 scan/Worker/Processor、PG 失败诊断、attempt=1、退避前不可领取；测试 SQL 仅推进 next_retry_at；第二次成功清理诊断 |
| C02 | NOT VERIFIED | TaskRecoveryIT 有永久错误状态层测试；尚无原文件摘要错误端到端注入 |
| C03 | NOT VERIFIED | TaskRecoveryIT 验证默认 5/15/45 秒 ±20%、4 次耗尽；不是完整流水线持续故障 |
| C04 | NOT VERIFIED | C01 故障发生在真实 Chunk 提交之后，恢复 ID 集合相同并最终发布；尚缺提交前后内容/顺序/区间完整快照比较 |
| C05 | NOT VERIFIED | 已有向量持久化回归不能替代首批提交后中断及请求记录证明 |
| C06 | NOT VERIFIED | 已有 ES 幂等回归不能替代完整流水线 Bulk 部分成功注入 |
| C07 | NOT VERIFIED | 未增加 ES 写入后验证前异常 |
| C08 | NOT VERIFIED | 未增加真实 VERIFIED 后异常及禁止重写探针 |
| C09 | NOT VERIFIED | 未增加真实提交后事务异常代理 |
| C10 | PASS | 复用 IndexPublishIT：真实 PG trigger 拒绝任务更新，三表快照完全回滚；移除 trigger 后合法领取权下成功发布 |
| C11 | NOT VERIFIED | IndexPublishIT 真实 PG 验证过期租约/epoch 拒绝；TaskRecoveryIT 数据库恢复和 Scheduler Mock 回归通过，尚缺同 JVM 活跃函数完整基础设施验收 |
| C12 | NOT VERIFIED | 协作超时及发布后不失败有逻辑回归；尚缺真实模型耗时端到端验证 |

没有生产代码修改。修正 TaskRecoveryIT 超时处理器：耗尽预算后显式调用 checkTimeout，符合协作式超时协议。首次回归暴露原测试返回后未检查超时，last_error_code 为 NULL；不通过在 Worker 发布后增加检查来改变生产语义。

## 实际命令

```text
mvn spotless:apply spotless:check -DskipTests test-compile
mvn test -Dtest=IngestionTaskProcessorTests,TaskExecutionTests,TaskSchedulerRecoveryTests,ConfigurationTests failsafe:integration-test failsafe:verify -Dit.test=TaskRecoveryIT,IndexPublishIT,IngestionPipelineIT
mvn spotless:apply spotless:check -DskipTests test-compile
mvn test -Dtest=IngestionTaskProcessorTests,TaskExecutionTests,TaskSchedulerRecoveryTests,ConfigurationTests failsafe:integration-test failsafe:verify -Dit.test=TaskRecoveryIT,IndexPublishIT
mvn failsafe:integration-test failsafe:verify -Dit.test=IngestionPipelineIT,EmbeddingPersistenceIT,EsIndexIT,IndexVerificationIT,StorageImportIT
git diff --check
```

编译与 Spotless 成功。初轮 IT 5 项中 TaskRecoveryIT 超时断言失败，修正后单元 26、PG IT 3 项全部成功，无跳过。日志 target/m1-13c-compile-final.log、target/m1-13c-baseline.log、target/m1-13c-recovery.log。相关最终 IT 日志 target/m1-13c-final.log。

本次不执行完整默认回归，不修改架构、依赖、迁移或发布条件；不推送、不合并、不进入 M1-13D。未发现已证明的生产缺陷，但故障矩阵存在验收缺口，阻断将 M1-13C 标为完成；M1-13D 前必须补齐上述 NOT VERIFIED 项及最终严格评审。
`m1-13c-final.log` 最终 BUILD SUCCESS：11 项 IT 全部成功，0 失败/错误/跳过（EmbeddingPersistenceIT 2、EsIndexIT 4、IndexVerificationIT 2、IngestionPipelineIT 2、StorageImportIT 1）。故障恢复后的 PG 向量维度、ES ID 集合/检索、MinIO 原字节读取及完整发布事实均由 IngestionPipelineIT 检查。

## 第二轮：C02～C08（本节更新第一轮对应结论）

基线 f83998d69dfbdfdbdc6ab4f9da1d1eeb6ee7a985，分支正确，工作区干净。仅修改 IngestionPipelineIT 和本记录；无生产代码、依赖、迁移、默认配置修改。C09/C11/C12 保持 NOT VERIFIED，不进入 M1-13D。

### 测试组织与真实环境

同一个小型测试辅助方法 runPipeline 共享容器/Context 配置与通用断言，8 个独立测试方法各自启动 Context、通过真实上传 API 建立隔离知识库/任务，每种模式分别验证 Markdown/TXT。正常首次成功与 503 恢复独立。类继承的 analyzerAndBm25 是 1 个 ES 基线测试；9 项总数中只有 8 项是导入测试方法，不能称作 9 个导入场景。无测试顺序依赖，不清库恢复；仅重试时间用 SQL 推进，不修改状态、attemptCount 或领取权。

沿用首轮列明的固定镜像：真实 PostgreSQL 17.6 + pgvector 0.8.0、ES 8.18.0 + IK、MinIO。运行日志显示实际 PG 17.6，TaskRecoveryIT 继续检查 server_version/extversion。真实 Spring ApplicationContext 中 Scheduler → Worker → Processor → Services/Repositories/事务执行；停止自动调度后显式串行 scan，不证明自动定时触发。模型使用本地 HTTP Stub、生产 OpenAiEmbeddingModel、text-embedding-v4/1024 快照校验。测试批次 Embedding=1、ES=1，不改变生产默认值。

### 本轮故障矩阵

| 场景 | 结果 | 注入、状态及恢复证据 |
| --- | --- | --- |
| C02 | PASS | 上传真实原件后使用 MinIO 客户端覆盖该对象，PG SHA 不变；真实 scan 判 VALIDATION_FAILED、FAILED/attempt=1/nextRetryAt=NULL，重复扫描不发布，API FAILED；不人工恢复永久错误 |
| C03 | PASS | Stub 持续 503；四次真实 scan 各检查 attempt/claimEpoch=1～4、MODEL_UNAVAILABLE、前三次有重试/第四次无重试；第五次扫描仍为4；完整 Chunk 快照不变，版本/文档各1，无 activeVersion/PUBLISHED/READY |
| C04 | PASS | retryAfterChunkCommit：503 位于真实 Chunk.initialize 提交后；恢复前后 json_agg 比较 id/chunk_order/content/heading_path/source_start/source_end 全集合，含数量/顺序，最终 READY |
| C05 | PASS | 第一批正常模型响应并真实写 PG，第二批及其 HTTP 重试持续503；故障时恰1个向量持久化且仍有NULL；记录完成向量/内容及全部请求输入；恢复请求不含已完成文本，原向量不变，完整Chunk快照不变，所有向量非NULL且1024维，最终READY |
| C06 | PASS | 测试代理在 EsIndexRepository.create 首批真实返回后抛 SEARCH_UNAVAILABLE；ES 数量>0且<PG。恢复走生产 create，已有ID触发409一致比较，缺失数据补齐；既有文档源内容不变，最终ID集合等于PG；409冲突拒绝复用 EsIndexIT 的真实部分成功/冲突用例 |
| C07 | PASS | 测试代理在 EsIndexService.index 真实返回后抛 SEARCH_UNAVAILABLE；故障时ES数量=PG、版本BUILDING、任务FAILED/API FAILED、无activeVersion；恢复保持PG全行/已有ES源内容不变，经真实验证最终发布READY |
| C08 | PASS | 测试代理在真实 verify 返回（事务已提交VERIFIED）后抛 SEARCH_UNAVAILABLE，首次发布未发生；恢复前记录调用次数及PG全行/ES源；恢复verify次数+1，initialize/embed/index次数不变，数据完全不变；原发布Service首次提交后PUBLISHED/publishedAt/activeVersion/SUCCEEDED/API READY完整 |

C06 是真实 ES 批次间已成功写入后的中断，不是单个 Bulk 内网络中断或响应丢失的验证；测试没有删除重建索引或改模型。EsIndexIT 另覆盖真实一项成功、一项409冲突且成功项保留。所有恢复成功场景同时检查完整发布事实、PG向量合法性、ES版本ID集合及中文检索、原MinIO字节；永久错误和耗尽场景不会发布。测试代理只包装真实服务调用，在方法返回后注入异常，没有 Mock Service 返回值。

### 命令与结果

```text
mvn spotless:apply spotless:check -DskipTests test-compile
mvn failsafe:integration-test failsafe:verify -Dit.test=IngestionPipelineIT
mvn test -Dtest=IngestionTaskProcessorTests,EmbeddingServiceTests,TaskExecutionTests,TaskSchedulerRecoveryTests failsafe:integration-test failsafe:verify -Dit.test=IngestionPipelineIT,TaskRecoveryIT,EsIndexIT,EmbeddingPersistenceIT,IndexVerificationIT,StorageImportIT
git diff --check
```

编译/Spotless成功，日志 target/m1-13c-r2-compile*.log；新增测试首轮9项全成功（8导入+1继承ES基线），target/m1-13c-r2-e2e-final.log。更早一次将 -DskipTests 与 Failsafe 放在同一命令导致IT跳过（target/m1-13c-r2-e2e.log），该次不计入验收。最终回归使用不含skipTests的独立命令，target/m1-13c-r2-regression.log。

未发现需要生产修复的缺陷。未调用供应商API，未执行完整默认回归。剩余发布提交响应丢失、领取权/活跃函数恢复和端到端超时仍须下一轮验证，三项完成并严格评审后才具备进入 M1-13D 的前置条件。
最终回归 BUILD SUCCESS：单元25项，IT 20项（EmbeddingPersistenceIT 2、EsIndexIT 4、IndexVerificationIT 2、IngestionPipelineIT 9、StorageImportIT 1、TaskRecoveryIT 2），0失败/错误/跳过。最终版9项已包含新增ES源内容快照断言。git diff --check通过，完整diff已审阅。

## 第三轮：发布响应与执行边界

基线 3968daf298a33336d2465723d43b1fbca92fc0ff，分支正确，工作区初始干净。仅测试及本文修改，无生产实现、依赖、迁移或发布条件变更。

新增 IngestionPipelineIT 四个独立测试方法，复用既有真实环境与 Markdown/TXT 上传路径；没有绕过双索引验证。为减少既有环境复制，仍沿用 runPipeline 测试辅助方法，未拆分新 IT，这是测试组织上的限制。

- C09：真实发布 Service 的事务代理返回后，外层测试代理一次性抛 TransactionSystemException；真实 Processor 查询 SUCCEEDED 后再次调用发布协议确认，真实 Worker 返回。最终检查 activeVersionId、PUBLISHED/publishedAt、SUCCEEDED、失败诊断清理、attempt=1 和 READY，并检查 PG 向量与 ES 完整集合、MinIO 原文。反向场景在真实 IndexPublishRepository.publish 更新三项事实后、真实发布 Service 事务提交之前抛同类异常，迫使真实发布事务回滚；此时 RUNNING 原领取者真实重试发布。不是直接 UPDATE 三项发布事实，也不是数据库网络断线。明确业务冲突仍由 IndexPublishIT 拒绝并比较完整三表快照。本轮未专项验证第二次确认再次异常，也未对两次发布调用间的 PG/ES 内容单独取快照；因此严格完整 C09 仍 NOT VERIFIED，已验证子路径见运行结果。
- C11：TaskRecoveryIT 新增真实 TaskService + TaskScheduler 测试。真实领取后通过 SQL 推进 lease_until（测试时间推进，并未等待十五分钟），旧领取者 fail 被拒绝。CountDownLatch 阻塞第一轮；第二轮发起后 Future 超时且数据库仍 RUNNING；第一轮退出后第二轮真实 recoverNext 写 WORKER_INTERRUPTED；推进 next_retry_at 后真实 claimNext 获得新 owner、epoch+1，旧领取者仍无法覆盖新 owner。过期发布/错误 epoch 发布拒绝沿用 IndexPublishIT 回归。仅证明同一 Scheduler 实例，不证明跨实例/跨 JVM 安全。
- C12：本地 Embedding HTTP Stub 返回响应前推进测试单调时钟，真实 Processor 使用一秒测试预算；模型请求前预算有效，响应后真实 EmbeddingService 检查超时，PG 无向量、ES/index/verify/publish 调用均未发生，真实 Worker/TaskService 写 TASK_TIMEOUT、attempt=1 和重试时间；Chunk 快照保留，API 不 READY。另一测试在真实 verify 返回后推进时钟，检查 VERIFIED、publish 调用为零；之后真实重试经 VERIFIED 路径发布 READY。不是实际等待十分钟或供应商请求。发布后不再检查截止时间由既有 TaskExecutionTests 保留，未新增端到端发布后超时注入，严格完整 C12 仍 NOT VERIFIED。

### 实际执行

```text
mvn spotless:apply spotless:check -DskipTests test-compile
mvn failsafe:integration-test failsafe:verify -Dit.test=IngestionPipelineIT#committedPublishResponseLost
mvn failsafe:integration-test failsafe:verify -Dit.test=IngestionPipelineIT,IndexPublishIT,TaskRecoveryIT
mvn test -Dtest=IngestionTaskProcessorTests,EmbeddingServiceTests,TaskExecutionTests,TaskSchedulerRecoveryTests,ConfigurationTests failsafe:integration-test failsafe:verify -Dit.test=IngestionPipelineIT,TaskRecoveryIT,IndexPublishIT,EsIndexIT,EmbeddingPersistenceIT,IndexVerificationIT,StorageImportIT
mvn failsafe:integration-test failsafe:verify -Dit.test=IngestionPipelineIT,TaskRecoveryIT,IndexPublishIT
git diff --check
```

聚焦提交丢失 1 项成功；较早回归 14 项 IT 成功，0 失败/错误/跳过。新增回滚断言编辑时发生括号错误，Spotless 拒绝解析；修正后重新格式化编译成功。此前失败后的命令仍启动过旧编译测试，该运行不作为最终验收证据。最终日志为 target/m1-13c-r3-final-fixed.log 与 target/m1-13c-r3-enhanced.log；单元 30 项成功。最终 IT 结果以下文为准。

未发现经测试证明需要生产修复的缺陷。不 Push、不 Merge、不进入 M1-13D；完整验收缺口仍阻断 M1-13D 前置条件。


### 第三轮最终结果与矩阵

修正后 target/m1-13c-r3-repair-compile.log：Spotless apply/check、编译和测试编译 BUILD SUCCESS。target/m1-13c-r3-repair.log：17 项 IT（IngestionPipelineIT 13、TaskRecoveryIT 3、IndexPublishIT 1），0 失败/错误/跳过，BUILD SUCCESS。13 项中包含一个继承 ES 基线，不代表13个故障场景。

target/m1-13c-r3-final-fixed.log：单元30项通过；IT26项中25通过、1失败，无错误/跳过。唯一失败为错误外层回滚注入（真实发布使用独立事务，外层回滚后仍 SUCCEEDED）；替换为发布 Repository 内抛异常后，上述17项重新全部通过。其余相关真实基础设施回归为 EmbeddingPersistenceIT 2、EsIndexIT 4、IndexVerificationIT 2、StorageImportIT 1，均通过。运行期间曾重叠启动 Maven，报告目录存在覆盖风险，结果数量依据各独立日志，不累加 target XML；修正回归为独立日志，不将失败运行宣称全绿。

| 场景 | 最终状态 | 本轮证据或剩余缺口 |
| --- | --- | --- |
| C01 | PASS | 本轮完整流水线重跑成功，注入/持久化/边界沿用第二轮 |
| C02 | PASS | 本轮原件摘要永久错误回归成功，测试覆盖 MinIO 原件 |
| C03 | PASS | 本轮四次耗尽回归成功，真实 PG attempt/epoch/诊断 |
| C04 | PASS | 本轮 Chunk 快照回归成功 |
| C05 | PASS | 本轮部分向量恢复及请求记录回归成功 |
| C06 | PASS | 本轮 ES 部分写入恢复及已有源不变回归成功 |
| C07 | PASS | 本轮 ES 完成后验证前中断回归成功 |
| C08 | PASS | 本轮 VERIFIED 恢复且不重写回归成功 |
| C09 | NOT VERIFIED | 真实提交后异常与真实回滚恢复子路径通过；第二次确认异常、调用间数据快照及明确冲突不二次调用的 Processor 专项断言未齐全 |
| C10 | PASS | IndexPublishIT 真实 trigger 拒绝任务更新后三表整体回滚；生产发布路径，无伪造成功 |
| C11 | PASS | 同一 Scheduler 实例活跃函数串行、真实租约推进/恢复/epoch递增及旧领取失效通过；发布拒绝由真实 IndexPublishIT 回归；测试状态准备仅租约/重试时间推进，新代际通过真实领取；不覆盖多实例 |
| C12 | NOT VERIFIED | 模型返回后写入前超时及 VERIFIED 发布前超时子路径通过，生产 Worker/Processor/Service；仅测试时间源替换、无生产开关；发布后超时仍只有既有逻辑回归，未补端到端注入 |

未证明生产 Bug，无生产修复。上述缺口不是架构性阻断，而是尚未完成的测试工作；本提交是实际故障测试的阶段性交付，不是第三轮最终验收通过。M1-13D 前置条件不满足。
