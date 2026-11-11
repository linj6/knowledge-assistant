# M2-07 检索综合验收证据

## 上轮结论：PARTIAL（历史记录）

本段为上轮阶段记录，当时尚不建议合并；本轮最终结论见文末。

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

## 本轮综合验收收尾

本轮起始 HEAD 63f24c18ad2dbf728464a3dc31574b82a47677e7，分支 feature/m2-retrieval，初始工作区干净。
上文单 Chunk 数值仅属于上一轮；以下为本轮多 Chunk 语料的独立结果。

### 新增与复用

扩展 RetrievalServiceIT 既有正式服务用例，不新增容器基础设施或 Failsafe includes。
新增 3 个独立测试：RetrievalFoundationTests 快照构造损坏分类、RetrievalApiTests 日志/API 脱敏、RetrievalServiceTests 失败阶段诊断。
综合 IT 仍为 2 项（包括继承的 ES 基线），其中一个方法覆盖正常排序、重复运行及两种依赖故障恢复。

### 双 KB 数据与 Top-K 前过滤

KB1=601：已发布文档 602/603 含 604、607；已发布文档 605/606 含 610、615。
未发布文档 608/609 含 616；文档 602 的历史版本 620 含 617。
KB2=611：已发布文档 612/613 含 614、618。
所有 Chunk 均写入真实 PG 和真实 ES 隔离索引；有效文档各含两个 Chunk。
查询 body，固定向量 e1，参数 4/4/60/4/2；合法向量前两维依次为 (.9,.1)、(.8,.2)、(.7,.3)、(.6,.4)，其余为零。
非法 616、617、614 使用 (1,0) 且正文重复 body 六次，高于部分合法候选的向量相似度与关键词集中度。
正式 SQL/ES 直接返回 4 个合法候选，未做检索后 Java 过滤，证明非法候选没有占用 Top-K。
KB2 独立 VECTOR/BM25 检索均命中自己的两个 Chunk，排除“另一 KB 没有可召回数据”的假隔离。
四策略最终 KB 均为 601，来源 Document/Version、正文、偏移及 headingPath 均有断言。

### 实际排序和分数（指定 IT 首次成功轮）

| Chunk | VECTOR rank / distance | BM25 rank / score | RRF rank / score | Rerank rank / score |
|---|---|---|---|---|
|604|1 / 0.006116251198662548|2 / 0.08413553|1 / 0.03252247488101534|2 / 0.1|
|607|2 / 0.02985753301900307|3 / 0.055323362|3 / 0.03200204813108039|4 / 0.1|
|610|3 / 0.0808549724108476|1 / 0.09713351|2 / 0.032266458495966696|3 / 0.1|
|615|4 / 0.16794970502633444|4 / 0.041210763|4 / 0.03125|1 / 0.99|

最终 VECTOR=[604,607]、BM25=[610,604]、RRF=[604,610]、RERANK=[615,604]，finalRank 均为 1、2。
RRF 逐项验证 1/(60+vectorRank)+1/(60+bm25Rank)，不相加原始分数。
Fake Reranker 对 615 给 .99、其他给 .1，断言每次接收全部 4 项且 topN=4；同分保留融合顺序。
三次重复完整 chunks 相等（含原始分数、来源、最终排名），snapshotHash 相等。耗时允许波动。
共同 hash=e8160cfa4a52845ab65f5d8e99a28b2a6b1f531f86cc45fe6cffac74b9e38bfc。

### 完整诊断

VECTOR 阶段 EMBEDDING→VECTOR；BM25 仅 BM25；RRF 为 EMBEDDING→VECTOR→BM25→FUSION；RERANK 追加 RERANK。
精确阶段列表断言同时排除重复和多余阶段。每阶段耗时非负、数量一致、候选 rank 从 1 连续。
EMBEDDING 候选 0，其他阶段各 4；finalTopK=2 没有提前截断诊断。
阶段分数与返回证据的 vectorDistance、bm25Score、rrfScore、rerankScore 对照断言；原始通道分数保留。
首次成功轮耗时为 0/78/978/1/16 ms，不断言固定时间。
失败阶段候选为空；允许保留已经成功阶段的 ID/分数诊断，但 FAILED 的结果 chunks 始终为空。
Embedding/Rerank 异常新增阶段断言；PG 失败没有伪造下游阶段，ES 失败没有伪造 FUSION/RERANK。
EMPTY_RESULT 继续由既有空快照/双路空结果测试明确区分，非降级。

### 日志与 API 脱敏

Logback ListAppender 仅捕获正式 RetrievalService 日志，正常及失败请求均执行 MockMvc 和 RequestIdFilter。
检查 requestId、KB、requested/actual strategy、status、phase、elapsedMillis、candidateCount、error 摘要字段。
带 QUERY_SENTINEL、CONTENT_SENTINEL、特殊向量值、API_KEY_SENTINEL、PROVIDER_RESPONSE_SENTINEL、EXCEPTION_SENTINEL 的输入/异常不会出现在日志；日志无 ThrowableProxy。
失败 API 响应无这些标识、异常类名及 stackTrace。正常 API 明确断言保留 CONTENT_SENTINEL 证据正文，不误判为泄漏。
这是应用日志边界验证，不是对所有第三方驱动内部日志的全局脱敏保证。

### PG/ES 实际故障与恢复

真实 Docker pause/unpause 本测试类 Testcontainers 容器，非 Mock、非 Compose、非容器停止重建。
暂停阻断实际服务响应，保留网络端口与数据，finally 无条件 unpause，JUnit 生命周期最终清理容器。
PG JDBC connect/socketTimeout=2 秒，Hikari connectionTimeout=1500ms；ES connect=1000ms、socket=1500ms，均仅测试客户端配置。
PG：正常四策略成功→暂停→四策略 SNAPSHOT_READ_FAILED、空 chunks/null actualStrategy/degraded=false、无阶段→恢复→四策略成功。
ES：正常成功→暂停→VECTOR SUCCESS，BM25/RRF/RERANK 均 BM25_FAILED 且空结果，不静默降级→恢复→四策略成功。
对外诊断仅安全枚举，不携带 JDBC/ES 原始异常。应用日志/API 异常脱敏另由捕获测试验证。
指定 IT 已实际执行 2 项，0 失败/错误/跳过，BUILD SUCCESS，1:26。

### 快照分类修复

PublishedVersionSnapshotRepository 仅在 of() 构造边界捕获 IllegalArgumentException 转为 SnapshotInvalidException，不匹配异常消息，不修改 SQL。
重复 Document Mapper 行通过正式 Repository→Service 验证为 SNAPSHOT_INVALID；DataAccessResourceFailureException 保持原对象，正式编排分类 SNAPSHOT_READ_FAILED。
修复及目标测试独立提交 ac7cb5b；其他验收测试单独提交。
初次测试编译因 ES record 访问器拼写错误失败，已修正；不计为通过轮次。
指定 27 项单测通过后又增加失败阶段测试，最终以 clean verify 汇总为准。

### 最终回归与结论

最终 mvn spotless:apply spotless:check '-DskipTests' test-compile 成功（42.026 秒）。
最终默认 mvn clean verify BUILD SUCCESS，耗时 12:28；Surefire 236 项、Failsafe 43 项，失败/错误/跳过均为 0。
默认 Failsafe 确实发现 RetrievalServiceIT：2 项通过，62.59 秒，包括本轮来源字段增强和真实故障恢复；未覆盖 includes。
未启用 online-model、未使用真实供应商 Key、未跳过 IT。无需新增 IT includes，因为扩展已在名单中的类。
最终日志位于忽略目录 target/m2-07-final-evidence/build.log、clean-verify.log，XML 位于标准 surefire-reports/failsafe-reports。
本轮最终 diff 及暂存区空白检查通过，改动限于 6 个文件（含独立修复提交），新增独立测试 3 项；既有综合 IT 方法被扩展，不重复计数。
最终结论 PASS：上一轮四项缺口已关闭，具备 M2 合并 main 的工程验收条件；本任务未执行合并、推送或开始 M3。
提交 SHA 由最终回复提供，避免文档自引用。
V1/P1 边界仍以上文为准：没有实现在线版本切换、旧快照保留租约、M3/M4 或在线模型语义质量评估。
