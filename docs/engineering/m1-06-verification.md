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
