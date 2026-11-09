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
