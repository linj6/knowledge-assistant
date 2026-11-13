# 包结构迁移与验收记录

## 范围与完成状态

分支 `refactor/package-structure`，R4 基线 `586fa94396846ca192a2180f7c7880ef76f5cc76`。R1 API 入口与简单模块、R2 实体与基础 Mapper、R3 行锁/容量锁/状态 SQL、R4 ES 共享能力与业务配置归属均已完成。R5 仅验收与更新本文及包结构规范，不删除 Repository、不修改业务代码。

五个模块的 Controller 均位于 controller，HTTP DTO 位于 dto；内部业务模型保留 model。复杂导入、检索、RAG 按功能组织。infrastructure 保留 AI、ES、存储、数据库技术、日志与 Web 支撑。未发现因迁包新增的无价值抽象。TaskProcessingRepository 较薄但本次保留；是否删除须另行评审。

## SQL 影响矩阵

当前共有 12 个 Java Mapper、10 个 XML。下列路径相对业务根包及 resources/mapper。五个基础 Mapper 均继承 BaseMapper，其中三个另有 XML。

|Entity / 表 / 基础 Mapper|专用 SQL 及 XML（省略 Mapper.xml 后缀）|Java 数据访问检查入口|
|---|---|---|
|knowledge.entity.KnowledgeBase / knowledge_base / knowledge.mapper.KnowledgeBaseMapper|knowledge/KnowledgeBase 容量 FOR UPDATE；retrieval/snapshot/PublishedVersionSnapshot 联查|KnowledgeBaseRepository 列表排序；DocumentImportRepository 容量锁|
|document.entity.Document / document / document.mapper.DocumentMapper|document/Document 行锁；document/DocumentQuery；ingestion/Task、IndexPublish；retrieval/vector/VectorSearch、keyword/KeywordChunk、snapshot/PublishedVersionSnapshot|DocumentImportRepository 去重、计数、对象引用；ChunkRepository、EmbeddingRepository、IndexPublishRepository|
|document.entity.IndexVersion / document_index_version / document.mapper.IndexVersionMapper|document/IndexVersion 行锁及 BUILDING→VERIFIED；DocumentQuery、Task、IndexPublish、VectorSearch、KeywordChunk、PublishedVersionSnapshot|DocumentImportRepository；ChunkRepository expected_chunk_count 条件初始化；EmbeddingRepository、VerificationRepository、IndexPublishRepository|
|document.entity.DocumentChunk / document_chunk / document.mapper.DocumentChunkMapper|DocumentQuery；ingestion/Embedding 条件向量更新与 version+1；VectorSearch、KeywordChunk|ChunkRepository 按版本/顺序读；EmbeddingRepository；PgIntegrityService、EsIndexService、引用及检索映射|
|ingestion.task.entity.ImportTask / document_import_task / ingestion.task.mapper.ImportTaskMapper|DocumentQuery 最新任务；ingestion/Task 领取、失败、恢复；IndexPublish 成功更新|DocumentImportRepository 最早任务；TaskProcessingRepository、TaskRepository、IndexPublishRepository|

其余专用 Java Mapper 按 XML 同名定位：document.mapper.DocumentQueryMapper、ingestion.embedding.EmbeddingMapper、ingestion.task.TaskMapper、ingestion.publish.IndexPublishMapper、retrieval.vector.VectorSearchMapper、retrieval.keyword.KeywordChunkMapper、retrieval.snapshot.PublishedVersionSnapshotMapper。保留这些功能边界，不继续合并。

R3 已移除 ImportCapacityMapper 与 VerificationMapper；容量锁归 KnowledgeBaseMapper，文档和版本锁归 DocumentMapper / IndexVersionMapper，markVerified 归 IndexVersionMapper。EmbeddingMapper 仅保留向量写入。验证及发布仍由 Repository 编排锁，发布保持 document→version→task，领取只锁 task。XML namespace、resultType、嵌套 Row 及 VectorTypeHandler 路径与源码一致。

修改字段必须同时搜索 XML、BaseMapper 调用和 LambdaQueryWrapper/LambdaUpdateWrapper；不能仅查 XML。DocumentQuery 的最新任务与上传的最早任务语义不同。跨表发布保留 ingestion.publish，发布快照与向量/关键字回填保留 retrieval。发布事实仍为 document.active_version_id，成对快照过滤不能简化。历史 Flyway V1～V5 只读，字段演进新增迁移。

## 核心调用链与边界

1. KnowledgeBaseController → KnowledgeBaseService → KnowledgeBaseRepository → KnowledgeBaseMapper。HTTP 参数与响应转换留入口，实体不直接暴露。
2. DocumentQueryController → DocumentQueryService → KnowledgeBaseService / DocumentQueryRepository → DocumentQueryMapper。列表按创建时间、id 倒序，批量装配版本和最新任务；证据读取校验库、文档、版本、Chunk 归属及发布事实。两个读取入口使用只读 REPEATABLE_READ，不能在拆 Mapper 时降低隔离。
3. DocumentImportController → DocumentImportService → OriginalFileStore / DocumentImportRepository。HTTP 完成文件校验、对象处理、去重及持久化任务，返回 ImportTaskResponse；任务状态查询不触发索引处理。上传数据库事务通过现有编程式事务边界管理，不能移动整个方法进长事务。
4. TaskScheduler / TaskWorker → TaskService → TaskRepository → TaskMapper，领取提交后再进入 IngestionTaskProcessor。处理器禁止已有事务，读原对象并核对摘要 → DocumentParserFactory → ChunkService.initialize → EmbeddingService.embed → EsIndexService.index → IndexVerificationService（PG/ES 验证）→ IndexPublishService.publish。Chunk 初始化、Embedding 批次写入、PG 验证与发布保持各自边界；模型/ES 网络请求不纳入整个流水线事务。失败经 Worker/TaskService 记录并按原策略重试恢复。发布锁为 document→version→task；领取只锁 task，归属快照不反向加锁。HTTP 与 Worker 以持久化任务、租约和 claim_epoch 为界，不能改成请求同步执行。
5. RetrievalController → RetrievalService → PublishedVersionSnapshotService/Repository；VECTOR 为 QueryEmbeddingService→VectorRetriever/Repository；BM25 为 KeywordRetriever→KeywordSearchRepository→KeywordChunkRepository；HYBRID_RRF 融合两路，HYBRID_RERANK 在融合后 RerankService。共享快照和查询向量，finalTopK 在末端应用，失败不隐式降级。
6. RagController → RagService → RetrievalService → ContextBuilder → AnswerGenerator → StructuredOutputParser → CitationValidator → RagAnswer。引用校验针对已选证据，DocumentQuery 的证据接口是后续 HTTP 读取，两者不可混为一个事务。

## 后续归属与遗留事项

新功能遵循 [包结构规范](package-structure-guidelines.md)：先确定业务所有者，再选择功能子包；不为目录对称新增层次。新表的 Entity 与基础 Mapper 归业务模块，跨表原子操作归所属功能。共享技术协议归 infrastructure，业务编排留业务包。

保留现有较薄 Repository；上传和 Embedding 内校验职责若要拆分，须单独评审事务与校验时点。本轮不作为缺陷整改。StartupConfigLogger 对业务默认值的装配依赖保留，不增加配置抽象。Architecture Spec 的 Boot 3.x 与 pom.xml 4.1.2 差异仍存在，未修改冻结文档。历史 engineering 文档清理属于后续开源准备。

## R5 验证

2026-11-13 验收通过，READY TO MERGE（本结论限本次包结构重构）。实际执行：

```powershell
mvn "-Dmaven.repo.local=D:\develop\repository" spotless:check test-compile test failsafe:integration-test failsafe:verify
mvn "-Dmaven.repo.local=D:\develop\repository" "-Dit.test=ChunkPersistenceIT,EmbeddingPersistenceIT" failsafe:integration-test failsafe:verify
git diff --check
```

Spotless、test-compile 通过；默认单元测试 373 项、默认 Failsafe 16 个类 43 项、补充持久化 2 个类 3 项，共 419 项，失败/错误/跳过均为 0，两个 Maven 命令均 BUILD SUCCESS。补跑原因是默认 Failsafe 白名单未含 ChunkPersistenceIT、EmbeddingPersistenceIT；未修改配置。MarkdownPerformanceIT 未纳入此次功能验收。

真实 Testcontainers PostgreSQL/pgvector、Elasticsearch/IK、MinIO 均实际运行；覆盖迁移、导入、租约恢复、向量持久化、PG/ES 完整性、索引发布、四策略检索。核心 API、DocumentQuery、RAG、静态 Web 资源与接口契约由默认单元回归覆盖；模型使用替身。静态资源全限定类名检查无缺失，旧实体与业务 Properties 路径搜索无残留。未发现阻塞级或严重行为缺陷；上述遗留事项为非阻塞维护建议。未执行在线 Chat、Embedding、Rerank 请求；未改 API、SQL、事务、ES Mapping、MinIO 路径或 Flyway。用户原有未跟踪文件及日志全部保留。
