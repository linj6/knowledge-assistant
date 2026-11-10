# M2-07 检索验收阶段证据

## 结论：PARTIAL

本记录不是 M2 完整验收通过声明。不建议据此合并 main。

## Git 基线与范围

分支 feature/m2-retrieval，起始 HEAD 2795e7ac228235b9bcf7f6c6fbb540ef949ce9e9，工作区干净。
已执行 git fetch origin，HEAD 与 origin/feature/m2-retrieval 差异 0/0。
未修改冻结 Spec、M1 发布协议、算法、依赖或 Migration。未调用在线模型。
架构文档 Spring Boot 3.x 与当前 4.1.2 的已知差异保持不变。

## 数据设计与现有可见性证据

VectorSearchIT 已有真实 SQL 覆盖 KB 过滤、发布快照过滤、未发布候选、非法归属、不同距离、同距离 chunkId 排序和 Top-K 前过滤。
KeywordSearchIT 已有真实 ES 排序和 KB/版本预过滤、真实 PG 补全、正文不一致整路失败及 terms 上限测试。
以上现有用例本轮随默认 clean verify 执行，不把历史报告直接当作本轮执行结果。
尚未补齐历史版本独立样例、双 KB 均可召回的四策略综合验收和全部非法元数据场景。

## 四策略实测对照

复用 RetrievalServiceIT 正式服务、真实 PG/pgvector 与 ES/IK、固定 1024 维查询向量以及 Fake Reranker。
固定 query 为 body，KB=601，Document=602，Version=603，Chunk=604。
参数 vectorTopK=20、bm25TopK=20、rrfK=60、fusionTopK=20、finalTopK=5。

| 策略 | 原始/最终 ID | vectorRank / bm25Rank / finalRank | 距离 | BM25 | RRF | Rerank |
|---|---|---|---|---|---|---|
| VECTOR | 604 / 604 | 1 / null / 1 | 0 | null | null | null |
| BM25 | 604 / 604 | null / 1 / 1 | null | 0.2876821 | null | null |
| HYBRID_RRF | 604 / 604 | 1 / 1 / 1 | 0 | 0.2876821 | 0.03278688524590164 | null |
| HYBRID_RERANK | 604 / 604 | 1 / 1 / 1 | 0 | 0.2876821 | 0.03278688524590164 | 0.9 |

各召回/融合/重排池和最终结果均为 1 项。共享 snapshotHash：
732e18650a6ab0ed941c2f836d095624429109466ccc88949050cd3c70cc21f2。
本轮基准阶段耗时 EMBEDDING=0、VECTOR=42、BM25=269、FUSION=5、RERANK=13 ms。
耗时不作为固定断言。新增三次重复请求，完整 chunks（含原始分数和排名）与 snapshotHash 相等断言通过。
新增独立测试方法 0，扩展既有 IT 方法 1。
单 Chunk 和固定向量只证明数据流及确定性，不证明真实语义质量或混合策略更优。

## 故障矩阵与诊断安全

现有 RetrievalServiceTests / RetrievalApiTests 回归覆盖 404、SNAPSHOT_INVALID、SNAPSHOT_READ_FAILED、EMBEDDING_FAILED、VECTOR_FAILED、BM25_FAILED、TERMS_LIMIT_EXCEEDED、FUSION_FAILED、RERANK_FAILED、INTERNAL_ERROR。
现有失败辅助断言检查空 chunks、null actualStrategy、FAILED 和 degraded=false；独立通道失败隔离、禁止混合单路降级及 Rerank 回退已有覆盖。
这些是 Mock/Fake 故障测试，不是真实网络故障注入。
日志实现审阅只输出请求与阶段摘要；本轮尚未新增日志捕获脱敏断言，不能认定该验收项完成。
诊断完整字段、阶段分数逐项对应及失败阶段候选的系统验收仍需补齐。

## 真实依赖故障注入

未实施 PG/ES 停机及恢复测试。Docker 可用，正常 IT 成功；不存在已证实的环境阻断，不以 Mock 代替真实故障证据。

## 工程回归

实际执行 mvn spotless:apply spotless:check '-DskipTests' test-compile，BUILD SUCCESS。
实际执行默认 mvn clean verify，BUILD SUCCESS，耗时 13:07；未启用 online-model、未覆盖 Failsafe includes、未跳过 IT。
Surefire 233 项通过，失败/错误/跳过均为 0。Failsafe 43 项通过，失败/错误/跳过均为 0。
日志保留在忽略目录 target/m2-07-evidence/build.log、clean-verify.log，XML 位于 target/surefire-reports 和 target/failsafe-reports。
git diff --check 和 git diff --cached --check 通过。未发现或修复生产缺陷。

## 未验证边界和 P1 待办

当前保证请求内共享不可变发布快照与双通道过滤一致性；Vector SQL 和 BM25 PG 补全仍检查实时 active_version_id。
V1 仅首次发布、发布版本不可变，不保证在线切换期间旧快照持续可读。
P1 需要单独设计版本保留和请求级读取语义，不在本任务引入租约或分布式锁。
真实语义质量、性能、在线供应商、M3/M4 均未验证。
完成 M2-07 尚需多 KB 综合语料、完整诊断断言、日志捕获、真实 PG/ES 故障与恢复证据；这些是实施缺口，不是环境阻断。

