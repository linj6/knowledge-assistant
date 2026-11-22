# M2-06 四策略编排、REST API 与诊断

## 架构与协议

RetrievalService 是统一入口，复用 M2-01～05 组件，不改变检索算法、SQL、发布协议和事务边界。
入口在依赖调用前验证 KB、query、1～4 个唯一策略以及每个策略的参数；默认参数为 20/20/60/20/5。

一次请求固定执行：发布快照 → Embedding → VECTOR → BM25 → FUSION → RERANK。
按策略需求省略不必要步骤，每个步骤最多一次；两个混合策略共享完整融合池。
快照来自 document.active_version_id，VECTOR/BM25 使用同一个不可变快照对象；不创建跨存储长事务。
Embedding 与 BM25 为可选 Bean，缺失时返回明确失败，不阻止应用启动。

POST /api/v1/retrieval/search（Controller 路径 /v1/retrieval/search，复用公共 /api 前缀）。
请求 strategies 数组是本次明确决策，与冻结 Architecture Spec 单数策略接口不同，不修改冻结文档。

```json
{"knowledgeBaseId":1,"query":"RabbitMQ 消息确认机制","strategies":["VECTOR","BM25","HYBRID_RRF","HYBRID_RERANK"],"parameters":{"vectorTopK":20,"bm25TopK":20,"rrfK":60,"fusionTopK":20,"finalTopK":5}}
```

parameters 可省略，提供时五个字段必须完整。响应 Result<RetrievalSearchResponse> 的 data.results 按请求顺序包含 chunks 与 diagnostics，不回显 query。
复用 RankedChunk 证据协议（不是数据库实体），保留原始排名和分数；Jackson 既有配置将 Long ID 序列化为字符串。
完整融合池交给 RerankService，最终组装时才应用 finalTopK 并从 1 连续设置 finalRank，诊断保留截断前候选。

## 状态、错误与可观测性

SUCCESS 表示非空成功候选；EMPTY_RESULT 表示成功空结果；FAILED 返回空 chunks、null actualStrategy，degraded 永远 false。
空快照返回 EMPTY_RESULT 并保留 Hash，不访问下游。单路空结果仍可融合；单路失败不能冒充混合成功。
Rerank 失败不返回 RRF 候选，不实现降级。

| 位置 | 分类 |
| --- | --- |
| 发布关系损坏 | SNAPSHOT_INVALID（明确 SnapshotInvalidException） |
| 其他快照读取失败 | SNAPSHOT_READ_FAILED |
| Embedding 或 Bean 缺失 | EMBEDDING_FAILED |
| 向量 Recall 失败 | VECTOR_FAILED |
| BM25 Recall 失败或 Bean 缺失 | BM25_FAILED |
| ES terms 超限 | TERMS_LIMIT_EXCEEDED |
| 融合异常 | FUSION_FAILED |
| 重排异常 | RERANK_FAILED |
| 通道未预期异常 | INTERNAL_ERROR |

知识库不存在仍抛既有业务异常映射 404；非法请求统一 400；合法请求中的策略失败返回 HTTP 200 成功 Envelope。
所有耗时用 System.nanoTime。保留 Retriever 阶段，增加 EMBEDDING/FUSION/RERANK，无重复阶段。
候选诊断只有 ID、rank、score；失败阶段输出为空，不伪造模型分数。
日志复用 RequestIdContext，只输出 requestId、KB、策略、状态、错误及阶段耗时/数量摘要；不记录 query、正文、向量、密钥、供应商响应或异常消息。

## 验证与边界

单测覆盖共享调用和固定顺序、各独立策略、空快照、结果顺序、完整候选后截断、连续 finalRank、原始排名分数、快照两类错误、Embedding/VECTOR/BM25/terms/FUSION/RERANK/内部异常以及可选 Bean 缺失。
API 测试复用 MockMvc、RequestIdFilter、ApiExceptionHandler 和 Long 序列化配置，覆盖默认/自定义参数、非法请求、404、策略失败 HTTP 200 与安全诊断。
RetrievalServiceIT 使用真实 PG 17.6 / pgvector 0.8.0 和 ES 8.18.0 / IK 8.18.0，正式快照、向量 Repository、ES Repository、PG 补全、RRF 和 RerankService；固定 QueryEmbedding 与 Fake Reranker，不调用 DashScope。
Failsafe 显式 includes 新增 RetrievalServiceIT。

首次单测暴露不可变 List.contains(null) 校验异常，已改为逐项 null 检查。审阅修复混合 finalTopK 大于单路池时的 Context 策略校验问题，并增加回归用例。
最终执行结果待本节收尾记录。

未验证在线模型、真实语义质量、性能/QPS/SLA、真实网络断线。固定向量只证明数据流，不证明语义能力。
M2-07 再做完整发布可见性、KB 隔离、故障矩阵与策略对照验收；本次不自动开始后续任务。

## API 响应示意

以下为空发布快照时的单策略结构示意（hash 为占位说明，不是实测值）：

```json
{"code":0,"message":"成功","requestId":"req-001","data":{"results":[{"chunks":[],"diagnostics":{"requestedStrategy":"BM25","actualStrategy":"BM25","degraded":false,"status":"EMPTY_RESULT","snapshotHash":"<合法快照 SHA-256>","parameters":{"vectorTopK":20,"bm25TopK":20,"rrfK":60,"fusionTopK":20,"finalTopK":5},"stages":[],"error":null}}]}}
```

成功非空时 chunks 返回完整 RankedChunk 证据字段；阶段 candidates 仅含 chunkId、rank、score，不含正文。失败时实际策略为 null、chunks 为空、error 为安全枚举。

## 本地验证命令

```powershell
mvn spotless:apply spotless:check '-DskipTests' test-compile
mvn '-Dtest=RetrievalServiceTests,RetrievalApiTests,RetrievalFoundationTests,QueryEmbeddingTests,KeywordRetrievalTests,KeywordProtocolTests,RrfFusionTests,RerankServiceTests' test
mvn '-Dit.test=RetrievalServiceIT,RetrievalFoundationIT,VectorSearchIT,KeywordSearchIT' failsafe:integration-test failsafe:verify
mvn failsafe:integration-test failsafe:verify
git diff --check
git diff --cached --check
```

显式 IT 最终 8 项通过（RetrievalServiceIT 2，含继承的 ES 基线 1；KeywordSearchIT 3；RetrievalFoundationIT 2；VectorSearchIT 1），失败/错误/跳过均 0。不是仅启动容器：四策略均断言真实命中 chunk 604、SUCCESS 与 finalRank。
生成日志和 XML 证据位于忽略的 target/m2-06-*.log、surefire-reports、failsafe-reports，不提交生成文件。

## 最终收尾记录

Spotless apply/check 与 test-compile 成功。默认 Failsafe 两个目标不带 -Dit.test 或 includes 覆盖，43 项通过、0 失败、0 错误、0 跳过，耗时 9 分 32 秒；其中 RetrievalServiceIT 2 项成功，确认默认发现并真实运行。显式 IT 8 项与默认结果有重叠，不累加为唯一测试数。
完整审阅生产编排、API DTO、测试、pom 与报告；未发现重复快照/Embedding/召回/RRF 调用或提前 finalTopK。
单测中 Mockito 重复设置抛异常的快照桩曾导致测试自身错误，已改 doThrow；不将失败轮次记为最终通过。
未执行 clean verify、真实供应商请求或性能评估。未 Push、Merge、Rebase，未开始 M2-07。提交 SHA 见最终回复，避免文档自引用。

最终指定单测 79 项通过、0 失败/错误/跳过：RetrievalServiceTests 17、RetrievalApiTests 5、RetrievalFoundationTests 3、QueryEmbeddingTests 4、KeywordRetrievalTests 4、KeywordProtocolTests 1、RrfFusionTests 4、RerankServiceTests 41。各测试方法可能包含多个输入场景，数字按 JUnit 独立测试计数。
四策略同时成功时 InOrder 验证快照、Embedding、VECTOR、BM25、RRF、Rerank 各一次，并拒绝额外交互；连续 finalRank 与同一快照对象单测通过。
结论 PASS，具备进入 M2-07 验收工作的条件，不表示 M2-07 验收已完成。
