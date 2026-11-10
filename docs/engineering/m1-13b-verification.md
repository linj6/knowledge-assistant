# M1-13B 真实基础设施正常导入验收

## 基线与范围

分支 feature/m1-ingestion-indexing；起始 HEAD 9d3c798369112556babcb5aa4ceb28959f54c2ff，工作区干净。未修改生产代码、迁移、依赖版本或发布协议。未进入 M1-13C。

## 环境与路径

复用 ElasticsearchIT 的 ES 容器及 StorageImportIT 的 PG/MinIO 容器定义，独立临时数据库和对象存储；不使用本地开发 PG 数据。

- PG：pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87；实际 PostgreSQL 17.6，pgvector 0.8.0。
- ES：knowledge-assistant-elasticsearch:8.18.0-ik8.18.0；继承的 analyzerAndBm25 测试检查分词与 BM25。
- MinIO：knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local。
- 本地 JDK HttpServer，生产 ModelConfiguration / OpenAiEmbeddingModel，text-embedding-v4、1024 维，批量响应按 input 数量构造；固定单位向量，对相同输入确定性返回，不衡量语义质量。无供应商网络请求。

MockMvc 使用 Context 中的真实 Controller；全部生产 Service/Repository 保持真实。Context 启动后取消后台 scheduling，再显式串行 scan → Worker → Processor，不依赖固定 sleep。此验收不证明自动定时触发。ES 和向量服务的外层事务拒绝保护仍启用；上传对象存储事务边界及发布事务回滚/幂等由既有 StorageImportIT / IndexPublishIT 覆盖。

## 场景与证据

Markdown 和 TXT 各自 REST 上传 202，检查 PENDING/BUILDING/无 activeVersion、查询 RECEIVED、originals 原字节读取。真实 scan 完成后检查 SUCCEEDED、所有 Chunk 原文 code point 区间、PG vector_dims=1024、ES Refresh 后版本 Chunk ID 集合一致及 Match 中文命中、activeVersion/PUBLISHED/publishedAt、查询 READY。

同知识库重复上传 200、原任务 ID、三张主表计数和正式对象计数不变。另一知识库相同字节上传 202，独立创建 Document。已发布版本 Chunk/Embedding/ES 构建入口拒绝重写，Chunk 全行快照不变。既有 IndexPublishIT 覆盖重复发布幂等，IndexVerificationIT 覆盖 ES 已存在但尚未发布的指针、版本及任务事实。

## 本地 Compose MinIO

只读核对 compose.yaml 默认目标、application.yml 默认 knowledge-assistant、.env/application-local.yml 未发现 Bucket 覆盖；既有容器 exited，数据卷 knowledge-assistant_minio-data 挂载 /data。启动既有容器只读列举 Bucket 为空，初次脚本 check 失败。按本次明确授权创建 knowledge-assistant，执行：

```text
docker exec knowledge-assistant-minio-1 mc mb ka-read/knowledge-assistant
python infra/minio/configure-staging-ilm.py --bucket knowledge-assistant
python infra/minio/configure-staging-ilm.py --bucket knowledge-assistant --check
```

配置及复核均返回 verified=true、prefix=staging/、expireDays=2、ruleCount=1。随后恢复容器停止状态。未删除对象/卷或改动其他规则；未等待两天观察实际删除。

## 验证命令

- `mvn spotless:apply spotless:check -DskipTests test-compile`：成功；迭代编译日志 target/m1-13b-compile*.log。
- `mvn failsafe:integration-test failsafe:verify -Dit.test=IngestionPipelineIT`：首轮 2 项成功，0 失败/错误/跳过；日志 target/m1-13b-e2e.log。
- 最终相关回归命令与数量见下方。

未执行完整默认回归、在线模型或故障恢复矩阵。单实例单 Worker、协作超时及现有租约约束不变。M1-13C 需独立实施中断、重试耗尽和提交响应丢失等真实故障注入，不能由本次正常路径通过推导。

最终命令：

```text
mvn test -Dtest=IngestionTaskProcessorTests,EmbeddingHttpStubTests,EmbeddingServiceTests,TaskExecutionTests,TaskSchedulerRecoveryTests,ImportTests,ConfigurationTests failsafe:integration-test failsafe:verify -Dit.test=IngestionPipelineIT,IndexVerificationIT,IndexPublishIT,StorageImportIT,EmbeddingPersistenceIT,EsIndexIT
```

BUILD SUCCESS：单元 35，IT 12，全部成功，失败/错误/跳过均为 0。IT 分布：EmbeddingPersistenceIT 2、EsIndexIT 4、IndexPublishIT 1、IndexVerificationIT 2、IngestionPipelineIT 2（含继承 ES 测试）、StorageImportIT 1。日志 target/m1-13b-final.log。StorageImportIT 中断开 PG 产生的连接警告属于既有故障补偿用例，最终通过。

`git diff --check` 通过；审阅 pom.xml 及新增测试/记录，修改仅增加 Failsafe 清单与正常路径集成测试。未发现生产集成缺陷，不修改生产代码。测试不直接观测三项发布事实的每个中间瞬间，事务原子性由生产发布事务与既有真实 PG 回滚测试共同支持；未增加网络事务探针或 M1-13C 故障框架。
