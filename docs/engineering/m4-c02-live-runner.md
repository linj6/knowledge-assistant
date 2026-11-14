# M4-C02 LIVE Retrieval Evaluation Runner

## 范围与入口

仅新增测试源码集 `LiveEvaluationRunner`、Mock 定向测试和本说明。生产检索行为不变；不上传、不建索引、不发布知识库，不执行 Chat/Rerank 或 Answer Evaluation。

入口：`com.lnjecit.knowledge.assistant.evaluation.runner.LiveEvaluationRunner`。在仓库根目录执行，使用既有 application.yml、环境变量及外部 config 配置（如需 local，用环境变量 `SPRING_PROFILES_ACTIVE=local`）。密钥不得放入 CLI 参数或提交到仓库。

以下命令会访问真实环境，是授权后使用的操作说明，本轮未执行：

```powershell
mvn test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.lnjecit.knowledge.assistant.evaluation.runner.LiveEvaluationRunner" "-Dexec.classpathScope=test" "-Dexec.args=--knowledge-base-id=800000 --chunking=FIXED_CODE_POINT --strategies=BM25 --parameters=20,20,60,20,5 --output=target/evaluation/m4-c02-live-bm25-v1"
```

费用单独获授权后，三策略示例参数为：

```text
--knowledge-base-id=800000 --chunking=FIXED_CODE_POINT --strategies=VECTOR,BM25,HYBRID_RRF --parameters=20,20,60,20,5 --authorize-embedding-cost --output=target/evaluation/m4-c02-live-all-v1
```

- knowledge-base-id、chunking 必填；chunking 支持 FIXED_CODE_POINT / MARKDOWN_STRUCTURE_AWARE，用于选择评分参照，不能改变知识库索引。
- strategies 默认 BM25；仅支持上述三种，拒绝重复策略及 HYBRID_RERANK。
- parameters 默认 20,20,60,20,5，依次为 vectorTopK,bm25TopK,rrfK,fusionTopK,finalTopK，使用已有 RetrievalParameters 校验。
- 包含 VECTOR 或 HYBRID_RRF 必须显式授权；在 Spring 启动前拒绝缺少授权的配置。BM25-only 强制关闭模型 Bean，不请求查询 Embedding。
- output 默认 target/evaluation/m4-c02-live-v1；只能使用 target/evaluation 下直接的 m4-c02-live-* 目录，始终拒绝覆盖。C01 目录不受影响。

## 执行流程及环境

先复用 FrozenRankingEvaluation 读取并核验 v1 的 Fixture、Gold、24 个确认池及来源原文字节；全局冻结指纹必须与 B03 一致。问题原文复用 m4-01-gold-evidence.json，逐题核对 Fixture 的 querySha256；12 题全部执行，不动态生成题目。

采用现有应用的非 Web、懒加载 Spring 上下文获取原 RetrievalService。命令行强制关闭 Flyway、SQL 初始化、Worker、Rerank，并设置 Hikari 只读连接；不接受任意 Spring 参数以覆盖这些约束。未创建第二套检索服务或新的生产配置。Chat Bean 不由入口获取或调用。复用现有 ModelConfiguration 的 Embedding 客户端及零重试配置。

只读 PG 查询已发布文档的 content_hash 作为内容身份核验输入；每道题一次 search(kb,query,strategies,parameters)，由服务共享 Snapshot 和 Query Embedding。不重试、不降级。服务抛异常时记录 SEARCH_EXCEPTION，避免输出原始异常内容，继续后续题目。

真实环境需要：已迁移且已有发布数据的 PG/pgvector、BM25/Hybrid 所需既有 ES/IK 索引及开启 assistant.elasticsearch.enabled、相邻 backend-interview-lab 原文字节；向量检索另需 text-embedding-v4 / 1024 FLOAT 的匹配索引及现有模型配置。不得为了联调启动 Docker、迁移、导入或重建数据。

## 评分适用性

SnapshotHash 仅绑定 KB / 文档 / 版本身份，不当作完整内容指纹。评分先核对冻结 Snapshot、PG 文档内容 Hash 与冻结来源 Hash，再核对返回 Chunk 的文档、版本、KB、正文及来源区间。当前仅支持冻结 v1 来源身份，不自动映射其他 KB 的文档 ID。

- Snapshot 不同：Evidence=GOLD_SNAPSHOT_MISMATCH，Ranking=INVALID_POOL。
- 来源 Hash 无法验证：Evidence=GOLD_SOURCE_MISMATCH，Ranking=INVALID_POOL。
- 已知 Chunk 的正文或身份变化：GOLD_CHUNK_MISMATCH / INVALID_POOL。
- 新 Chunk：Ranking=PENDING_REVIEW。若其文档/版本已有可靠来源映射，原文字节 SHA 相同，且经过已有 DocumentParserFactory 得到的来源区间与正文完全一致，Evidence 可独立评分；否则 GOLD_CHUNK_UNVERIFIED，不记零。
- Query 与 Gold Hash 不同：GOLD_QUERY_MISMATCH / INVALID_POOL。
- FAILED 检索不评分，分母为零。EMPTY_RESULT 原样归档，适用性核验通过后转换为指标引擎的空 SUCCESS，按既有语义贡献零值。

直接调用 EvidenceMetrics.calculate / RankingMetrics.calculate，无第二套公式。宏平均复用 FrozenRankingEvaluation.mean，保留 numerator / denominator / value；JSON 不舍入，报告 HALF_EVEN 三位。人工语义标签及答案质量不重算。

## 四份产物

在同级临时目录完成后发布，不覆盖已有目录；输出失败抛 OUTPUT_ERROR 并清理临时产物。

- manifest.json：LIVE、runnerVersion、dataset、运行配置及费用授权标志、冻结输入指纹、问题文件来源及 SHA、PG 来源 Hash、实际 Snapshot 集合、检索和指标状态计数、三个非 manifest 产物的 SHA-256。
- retrieval-results.jsonl：逐题 Query、请求/实际策略、参数、SnapshotHash、SUCCESS/EMPTY_RESULT/FAILED、Error、完整 Chunk 排名/正文/来源/分数、Diagnostics 阶段耗时。服务整体抛异常时记录安全错误码，未获得的 Diagnostics 不伪造。
- metrics.json：逐题 Evidence/Ranking 状态、可评分分母、@3/@5 明细及按策略汇总。未评分项不提供虚假数值。
- report.md：执行状态、不可评分原因、关键 @5 宏平均及有效分母、数据来源。

运行状态 COMPLETED 仅表示请求执行完毕，不表示质量全部可评或通过；有失败为 PARTIAL_FAILURE，全部检索失败为 FAILED。指标状态独立保留。

## 验证与待办

仅 Stub/Mock 验证，不启动 Spring 真实上下文或任何外部服务。覆盖费用门禁、参数和目录限制、多策略同题合并调用、SUCCESS/EMPTY_RESULT/FAILED 转换、Gold/来源/Query/Snapshot 不匹配、新 Chunk、已知 Chunk 正文变化、失败状态输出、产物 Hash 和拒绝覆盖。

真实联调未执行。12 题 BM25-only：12 次服务调用、0 次 Embedding；三策略合并：12 次服务调用，非空有效快照下最多 12 次单 Query Embedding 请求（非 24 次），另有 PG/ES 查询。空快照或早期失败可能减少模型请求；实际费用取决于供应商当时定价和输入 Token，本轮未查询价格或发生费用。需要用户另行明确授权运行配置、知识库和模型费用后才能联调。

本轮不 Commit、Push、Merge。不进入 C03 或 Answer Evaluation。

实际验证结果：Spotless apply/check、test-compile 通过；最终定向命令 mvn spotless:check test -Dtest=LiveEvaluationRunnerTests,OfflineEvaluationRunnerTests 共 7 项通过（C02 4 项、C01 3 项），0 failures / errors / skipped。git diff --check 及三个新增文件的空白检查无错误。未运行真实 Spring 上下文、PG、ES、Docker 或模型请求，未做全量回归。READY FOR C02 LIVE VALIDATION：是，仅表示代码和 Mock 验证完成，真实环境验证待单独授权。
