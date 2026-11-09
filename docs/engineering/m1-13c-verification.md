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
