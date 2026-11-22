# M1-10C 双索引统一验证与 VERIFIED 转换

## 范围与统一流程

基线 `ba3e2432b3d402ba252653ab2adc8139cf692119`，分支 feature/m1-ingestion-indexing，实施前工作区干净。本轮新增 IndexVerificationService.verify(documentId, indexVersionId)，不实现发布或 Worker。

1. 拒绝在已有 PG 事务中调用统一入口。
2. PgIntegrityService.snapshot 在独立 REPEATABLE_READ 只读事务中复用完整 PG 校验，并产生同一批数据的不可变快照。事务结束后才返回调用方。
3. 将快照中同一批 EsChunkDocument 交给既有 EsIntegrityService。复用 Mapping 校验、Refresh、完整 Scroll、ID/六字段比较及实际 IK/BM25 搜索，不重复实现。
4. PgIntegrityService.complete 开启新的短事务，通过 VerificationRepository 按 Document → IndexVersion 顺序复用 EmbeddingMapper XML 行锁。
5. 在锁内重新执行完整 PG 校验和快照读取，比较整个快照，包括记录 version；只有相等时才执行 Mapper XML 中 BUILDING → VERIFIED 条件更新，必须恰好影响一行，否则抛出安全错误并回滚。
6. 事务提交后返回文档/版本 ID、预期数量、ES 数量、样本数量、verificationStatus 和本轮 verifiedAt（诊断时间，未写入新数据库字段）。

## 快照及状态语义

快照是 record 和 List.copyOf 构成的不可变数据，不返回 Document、IndexVersion、DocumentChunk 等可变持久化实体。覆盖文档/知识库/版本标识、预期数量、版本状态、模型/维度/切分配置、文档元数据和创建时间；每个 Chunk 包含全部 ES 六字段、记录 version、来源范围、headingPath 和 embedding 文本。比较 embedding 本身也可以发现未递增 version 的向量变化；不复制向量解析逻辑。

EmbeddingRepository 增加只用于验证的读取入口，允许 BUILDING 和未发布 VERIFIED，复用原有校验。原有 read/write 和 M1-10A verify 仍只接受 BUILDING，不放宽生成/写入协议。

重复验证已 VERIFIED 版本时，仍重新验证完整 PG、真实 ES 和锁内快照；全部通过才返回成功，不再次执行状态更新。PUBLISHED、FAILED、非空 active_version_id 或 published_at 均拒绝。ES 验证期间发生状态改变，即使最终状态本身合法，也不能与原快照相等而误报成功。

本轮不写 Document.active_version_id、不写 published_at、不修改 ImportTask、不标记 SUCCEEDED。先前 VERIFIED 版本复验失败只报告失败，不自动降级状态或修复索引。

## 事务与边界

外部 ES 操作全部在两段 PG 事务之间；最终事务没有外部调用。初始一致快照无行锁，最终事务按既有 Document → IndexVersion 顺序锁定，并在同一事务中完成重新读取、比较及条件更新。Repository 只封装锁和更新，Service 管理业务判断和事务。

沿用单 Worker 及合规写入者使用相同行锁的约定。忽略锁协议的外部写入者仍可能在复核后改变 Chunk，ES 在搜索检查后也可能被其他进程修改；本轮不是跨存储原子事务，不实现 Epoch/Fencing 或并发自动修复。快照正文、向量、对象 Key 等不写日志或错误正文。BM25 样本验证仍只证明指定 Chunk 在 ID 和业务归属过滤下可以通过正文 match 命中，不证明普通 Top-K 召回效果。

## 修改文件

- ingestion/verification/IndexVerificationService.java：统一编排和结果。
- ingestion/verification/PgIntegrityService.java：不可变快照、共享 PG 校验及最终短事务。
- ingestion/embedding/EmbeddingRepository.java：最小验证读取扩展，复用向量校验。
- ingestion/verification/VerificationRepository.java、VerificationMapper.java、resources/mapper/ingestion/VerificationMapper.xml：已有锁协议及条件状态更新，业务 SQL 全部位于 XML。
- IndexVerificationIT.java：真实 PG+ES 测试及故障注入。
- pom.xml：注册新增 Failsafe IT。
- 本记录。

## 测试设计及证据

新增测试使用仓库固定 PG digest、knowledge-assistant-elasticsearch:8.18.0-ik8.18.0 和禁止拉取策略；PG 是本次容器独立数据库，ES 只创建随机 m110c-* 索引，并在 finally 精确删除。没有触碰共享业务索引或调用在线模型。

IndexVerificationIT 一个方法内部覆盖九组场景：完整一致成功及重复验证、缺失 ES Chunk、多余 ES Chunk、ES 正文冲突、缺少 Embedding、真实 ES 验证后改变 Chunk.version、改变 Chunk 正文、改变版本为 FAILED、通过独立测试数据库触发器令最终 UPDATE 返回零行。失败不进入 VERIFIED；外部注入的 FAILED 保持 FAILED，其余初始 BUILDING 失败场景保持 BUILDING。触发器仅用于本次独立数据库并精确清理。

测试同时检查不可变集合、拒绝外层事务、外部 ES 调用时无 PG 活跃事务、active_version_id/published_at 仍为空、ImportTask 完整记录不变，以及缺失/冲突/多余数据未被自动修复。成功后删除 ES 文档再重复验证必须失败，已有 VERIFIED 不被自动倒退。测试使用真实客户端完成外部验证后注入 PG 变化，不用 Mock ES 成功证明跨存储正确性。继承的基础 analyzerAndBm25 另计一个测试。

## 实际验证结果

- `mvn spotless:apply spotless:check -DskipTests test-compile`：最终 BUILD SUCCESS。格式化、格式检查、主代码及测试编译通过。
- `mvn '-Dtest=PgIntegrityIT,EsIntegrityTests,EsIntegrityFailureTests,EsIndexTests,EsIntegrityIT,IndexVerificationIT' test`：首轮和最终版本均 14 项通过，0 失败/错误/跳过。首轮后整理了 Service/Repository 事务分层并增加外层事务拒绝断言，最终对完整指定范围重跑通过。
- 最终范围：离线9项（EsIndexTests 6、EsIntegrityTests 2、EsIntegrityFailureTests 1）；真实基础设施5项（PgIntegrityIT 1、EsIntegrityIT 2、IndexVerificationIT 2）。新增双存储方法内部九组场景，不记为九个独立 JUnit 测试。
- 新增测试实际断言 PostgreSQL 17.6、pgvector 0.8.0、Elasticsearch 8.18.0、IK 8.18.0；Flyway V1～V5 在独立测试库执行成功。真实 ES 数据缺失/多余/冲突及 PG 数据/状态变化均不被误判为可提交。
- `git diff --check`、`git diff --cached --check`：通过，已审阅完整 diff；新增业务 SQL 仅在 VerificationMapper.xml，复用原有锁 XML，未修改迁移或 ES 六字段协议。

## 未验证能力与未关闭问题

未运行全量 clean verify、真实网络中断/响应丢失注入、多 Worker 并发、性能基准或正式 Worker E2E，不把局部回归写成全量验收。纯空白 Chunk 与 Embedding 输入协议的既有问题仍未修复，PG 验证继续明确拒绝空白正文。协议外写入及跨存储检查后的变化边界如上，不通过分布式机制扩展本轮范围。

没有设置发布指针、发布时间、任务成功状态或执行自动修复。VERIFIED 是诊断状态，不是发布事实；M1-11 首次发布尚未实施。本轮到 M1-10C 停止。
