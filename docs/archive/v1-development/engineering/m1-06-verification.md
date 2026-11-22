# M1-06 Embedding 与 PostgreSQL 向量写入

## 流程与快照

EmbeddingService.embed(documentId, versionId) 读取现有 Chunk，不解析原文、不生成 ID。执行前检查 Document/IndexVersion/KB 归属、BUILDING、publishedAt、activeVersionId、正 expected_chunk_count、实际数量、连续顺序及唯一非空 ID。执行后重新读取并检查全部向量、ID、正文、标题和来源范围，保持版本状态不变。

复用 Spring AI EmbeddingModel.call、EmbeddingRequest、EmbeddingResponse 和 Embedding.index，以及 ModelResponseChecks 和 ModelFailures。当前 OpenAiEmbeddingOptions 不提供 Chat Options 的 mutate()；采用保守策略：实际 OpenAiEmbeddingModel.getOptions() 的模型和维度必须与持久化快照完全一致，否则拒绝。请求 options=null 保留客户端配置（含超时和零重试），不修改共享对象，不用 application.yml 覆盖快照。不支持未经验证的其他客户端类型。既有本地 HTTP 测试确认默认配置的模型和维度实际进入请求。

## 批量与校验

assistant.embedding.batch-size 默认16，允许1–16，启动及执行时校验。按 chunk_order 顺序处理 NULL embedding，批次串行；单条正文最多800 Unicode码点，与当前 M1-05 窗口匹配，超出拒绝，不截断。该保守业务限制不是供应商精确 Token 计数；供应商拒绝仍按既有安全模型分类处理。

写入前复用数量、index 唯一/范围/完整覆盖、1024维及有限 float 校验，按响应 index 回填输入位置。Arrays.toString(float[]) 产生现有 VectorTypeHandler 接受的向量文本，使用 PostgreSQL OTHER 绑定。Repository 再检查全部文本为有限1024维，读取既有向量也校验。

## 条件 SQL 与事务

EmbeddingMapper 仅更新 embedding，使用 id、index_version_id、document_id、knowledge_base_id 及正文、顺序、来源、标题条件；JOIN 版本和文档检查归属，要求模型 text-embedding-v4、维度1024、BUILDING、published_at IS NULL、active_version_id IS NULL、embedding IS NULL。不使用 updateById，不修改 V1～V4。

每批 Repository.write 独立事务；Service 拒绝在已有事务内运行，模型请求不进入数据库事务。条件更新为0时重读：版本失效明确失败、记录缺失明确失败、仍为NULL或元数据不一致为冲突，已完成且元数据一致则保留原向量。数据库异常转为固定无cause消息。整批提交或回滚，已提交前批保留；调用方响应丢失后重试读取非NULL记录并跳过。不持久化模型结果缓存，未提交结果可能再次调用模型，是可接受的 at-least-once 行为。

## 验证与边界

使用可控 OpenAiEmbeddingModel mock（等同 Fake）验证17条的16+1批次、乱序index、第二批失败后仅补剩余、完成后不再请求、非法配置、数量不足、重复index、错误维度、NaN/Infinity整批不写。复用既有模型协议和本地HTTP回归。

真实 Testcontainers 使用仓库固定 pgvector 0.8.0 / PG17 镜像，覆盖1024维写读、维度拒绝、NULL更新、幂等、触发器注入第二条写入失败并确认回滚、元数据及稳定ID回归。无真实供应商请求，无ES/MinIO请求。

尚未实现 Worker/fencing、发布互斥、双索引验证或发布；条件 SQL 不代表解决全部并发竞态。未执行全量 clean verify。输入上限是当前业务保守上限，未进行模型Token精确预检、吞吐或内存基准。

## 本轮实际执行结果（2026-11-08）

- `mvn spotless:apply`、`mvn spotless:check`、`mvn -DskipTests test-compile`：最终执行成功。
- `mvn '-Dtest=EmbeddingServiceTests,EmbeddingBaselineTests,ModelCapabilityTests,EmbeddingPersistenceIT' test`：最终17项通过，0失败、0错误、0跳过（3+3+10+1）。真实 PostgreSQL 17.6，V1～V4迁移成功。
- `git diff --check`、`git diff --cached --check`：通过，完整新增文件diff已审阅。

首轮编译发现 Embedding Options 没有 mutate()，改为实际客户端配置与快照一致性校验后编译通过。过程中一次构建因并行编辑触发格式检查失败，最终停止编辑后重新格式化并完整执行上述命令通过，不将中间失败视为通过。

测试边界：跨批次模型失败恢复使用可控模型及内存Repository模拟；真实PG测试独立验证批次提交、条件写入、回滚和重试，尚未将17条跨批恢复与真实PG组合为同一个端到端故障注入场景。未故障注入提交后网络断连，响应丢失恢复依据重复调用跳过与持久化重读协议；未执行并发Worker或发布竞态测试。


## M1-06 评审修正（基线 a20e7db）

业务SQL迁至 `mapper/ingestion/EmbeddingMapper.xml`，namespace与接口一致，所有业务参数使用 `#{}`，保留VectorTypeHandler。Java Mapper仅保留方法及参数；采用MyBatis-Plus默认 `classpath*:/mapper/**/*.xml` 加载路径，真实PG测试通过该Mapper实际执行更新。代码规范新增业务SQL统一XML规则。

EmbeddingProperties统一 DEFAULT_BATCH_SIZE/MAX_BATCH_SIZE（均16），Service读取校验后的配置，Repository使用同一上限，因此调小配置不会产生层间冲突。IndexVersion模型和维度是业务权威快照：Service检查实际客户端配置，Repository按版本维度校验；SQL用绑定模型/维度条件匹配。VectorSchema.DIMENSION集中表达当前Flyway物理约束1024，任何其他维度在模型请求前拒绝，不能通过配置扩展schema支持范围。没有修改application.yml默认模型、持久化快照或Migration。

写入前每条待写向量仅完整解析一次；条件更新为0时仅按ID读取对应Chunk，并重查Document/IndexVersion及归属、状态、发布时间、发布指针和快照，仍检查顺序和全部原文元数据；不再每次重载整个版本Chunk集合。

新增离线测试覆盖模型ID不一致、客户端维度不一致及快照与物理维度不兼容，全部在请求前拒绝。真实PG组合场景使用11680个ASCII字符，经FIXED_CODE_POINT v1得到17条Chunk；模拟模型请求批次为16、1（失败）、1（重试），数据库首批16条已提交，重试只补最后1条，完成后再次调用不请求模型。另验证不同值不能覆盖已有向量，以及模型调用内将版本变为VERIFIED后写入失败且向量仍NULL。

空白协议复现：原文为 `a + 2000个空格 + b`，固定窗口确实生成纯空白Chunk；Embedding当前整批输入校验拒绝、该批不请求模型。本次不改FIXED_CODE_POINT v1。后续须明确决定是否支持空白向量、改切分新协议或定义其他处理策略；不能静默跳过，否则数量和完整性协议失配。

保留并发发布互斥/fencing和提交后网络断连故障注入边界；本次没有实现Worker、发布或新状态机，没有在线模型请求或全量clean verify。此前“真实PG跨批恢复尚未组合”的记录为历史状态，本节补充其组合验证。

本轮实际验证：`mvn spotless:apply`、`mvn spotless:check`、`mvn -DskipTests test-compile` 成功；`mvn '-Dtest=EmbeddingServiceTests,EmbeddingBaselineTests,ModelCapabilityTests,EmbeddingPersistenceIT' test` 共18项通过（4+3+10+1），0失败、0错误、0跳过，包含真实PostgreSQL 17.6及V1～V4迁移、XML实际更新及跨批恢复。`git diff --check`通过。首次脚本受系统默认GBK编码影响，修正新增文件为UTF-8后重新格式化、编译和测试成功。

## Chunk 记录级乐观锁（基线 ab2e9ec）

新增V5：document_chunk.version INTEGER NOT NULL DEFAULT 0，不修改V1～V4，不新增索引；现有主键足以定位更新行。ChunkService明确初始化version=0，MyBatis按字段映射读取。该version是记录级版本，与IndexVersion索引构建版本、Worker fencing均不同，不使用MyBatis-Plus乐观锁拦截器。

Embedding XML以ID、索引版本、归属、expectedVersion及NULL embedding定位；SET embedding同时SET version=version+1。正文、标题和范围不再逐字段放入WHERE。EXISTS保留版本BUILDING、未发布、发布指针为空、文档/KB归属和模型/维度快照条件。Repository传入模型调用前读取的Chunk.version，写入后不修改旧内存快照；批次失败数据库embedding和version一起回滚。受影响行为0时按ID重读：版本状态失效拒绝、归属异常拒绝、embedding仍NULL且version变化报告乐观锁冲突；已完成且元数据一致则保留已有向量和数据库版本号，不覆盖。

未来任何Chunk正文/来源更新必须原子递增version；绕过版本协议的直接SQL修改不受此乐观锁保护。本次不提供正文更新、重建API或Worker，不声称解决发布互斥和fencing竞态。整数版本溢出由PostgreSQL拒绝并回滚，不回绕复用旧版本。

真实PG测试补充：新建0、写向量后1、旧版本重试保留ID/向量/version、两个持有相同版本的更新只有一个成功、version已递增但embedding仍NULL时旧结果被拒绝；SQL层验证跨索引版本、模型/维度不匹配、三种非BUILDING状态、非空发布指针均拒绝。触发器令同批第二条失败，验证首条embedding与version均回滚。保留17条16+1跨批恢复。独立从Flyway target=4插入历史Chunk后升级V5，移除新增version字段后逐字段比较前后记录完全一致，新增version=0。

本轮实际命令：`mvn spotless:apply`、`mvn spotless:check`、`mvn -DskipTests test-compile` 全部成功；`mvn '-Dtest=EmbeddingServiceTests,EmbeddingBaselineTests,ModelCapabilityTests,EmbeddingPersistenceIT,ChunkPersistenceIT,DocumentLifecycleMigrationTests' test` 共21项通过（4+3+10+2+1+1），0失败/错误/跳过。将双执行者场景改为分别读取相同版本并用两个CompletableFuture并发调用真实Mapper后，再次执行格式化、格式检查、test-compile及 `mvn '-Dtest=EmbeddingPersistenceIT' test`，2项通过，0失败/错误/跳过；并发更新结果为1和0。真实PostgreSQL 17.6、仓库固定pgvector镜像，V4到V5升级及新库V1～V5均成功。完整diff已审阅，`git diff --check`通过。未调用真实供应商、未执行全量测试或Worker/发布竞态验证。

## 短事务行锁与最终 SQL 简化（基线 c91b807）

EmbeddingRepository.write 在正式Spring事务入口内固定先 SELECT Document FOR UPDATE，再 SELECT IndexVersion FOR UPDATE；只读取一次锁定的版本快照。持锁后检查文档/版本/知识库归属、BUILDING、publishedAt、activeVersionId、正数量与连续Chunk顺序、模型配置和版本快照一致、维度符合物理schema、Chunk身份/来源元数据、记录版本和整批向量。Mapper的独立XML查询负责加锁；UPDATE只包含ID、index_version_id、expectedVersion、embedding IS NULL四项条件，原子写embedding并递增version，不再传model/dimension或使用EXISTS。模型配置仅用于与持久化快照作一致性检查，不能覆盖快照；Service仍检查实际客户端Options。

已有向量在事务内按ID核对元数据并校验后幂等跳过；未完成记录版本不一致明确失败。实际UPDATE为0表明记录异常或未遵循锁协议的竞争，明确冲突并整批回滚，不把未知结果静默视为成功。数据库写入异常仍只抛固定安全消息，不带原文/向量或原始cause。Chunk.version保持启用，不能用Document/IndexVersion锁替代。

锁协议：未来所有改变版本状态、模型/维度、publishedAt或Document.activeVersionId的流程，以及影响Chunk归属/正文/集合的流程，必须在同一事务中按Document→IndexVersion顺序持锁到提交（若涉及多个对象，应按各类ID升序），正文修改还须递增Chunk.version。即使直接UPDATE相关父行也会受已有行锁阻塞，但多行流程仍必须遵循统一顺序以避免死锁。当前未实现发布，M1-09必须延续此协议并完成跨发布互斥/fencing验收；本轮锁不是Worker fencing，不宣称最终发布竞态已解决。

EmbeddingService仍拒绝在外部事务中发起模型调用；每批模型调用完成后才进入Repository短事务。行锁只覆盖批次校验与写入，跨批允许部分完成，保持16+1失败恢复。

测试改为通过Repository正式代理事务入口验证非BUILDING、发布指针、publishedAt、模型/维度不一致和跨版本归属拒绝；双执行者通过Repository并发写相同Chunk，数据库仅增一次版本，后到者幂等跳过。锁测试在外层测试事务中调用正式入口，另一个事务按同样顺序申请锁并修改状态，使用pg_blocking_pids确认数据库实际阻塞，提交后才完成状态转换；随后Repository写入明确拒绝。保留模型调用期间状态变化、V4→V5、旧版本、回滚、16+1恢复及空白Chunk协议回归。外层事务仅用于测试延长锁观测窗口，不包含模型请求。

本轮最终实际执行：`mvn spotless:apply`、`mvn spotless:check`、`mvn -DskipTests test-compile` 成功；`mvn '-Dtest=EmbeddingServiceTests,EmbeddingBaselineTests,ModelCapabilityTests,EmbeddingPersistenceIT,ChunkPersistenceIT' test` 共20项通过（4+3+10+2+1），0失败、0错误、0跳过，真实PostgreSQL 17.6及固定pgvector镜像。Repository入口的双执行者幂等竞争、实际锁阻塞、状态拒绝、批次回滚和16+1恢复均通过。`git diff --check`通过，已审阅完整diff。Flyway V1～V5未修改；未执行全量测试、在线模型或真正发布流程验证。
