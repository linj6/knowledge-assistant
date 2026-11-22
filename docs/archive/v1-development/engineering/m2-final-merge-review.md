# M2 最终合并审查

日期：2026-11-11。审查范围为 main 到 M2 分支的完整历史及最终状态；仅新增本报告，不修改生产代码，不 Merge/Push/Rebase。

## Git 基线

起始分支 feature/m2-retrieval，工作区干净。起始 HEAD 与 fetch 后 origin/feature/m2-retrieval 均为 5e814b48049df3cce7fba5705ec023bc6d715317。origin/main 与共同祖先均为 c5a61eb97d544c1a3507711d2fbf4188b4fb5c10。ahead/behind=14/0，58 个文件，4526 新增、4 删除。main 是祖先，当前基线不存在分叉合并冲突；未实际合并。报告提交后本地将额外 ahead 一个文档提交，不能仍称本地与远端完全一致。

## 14 个提交

| 提交 | 审查摘要 |
|---|---|
|e075598|M2-01 快照、协议、容量锁与并发测试；容量为既有配置基线补齐|
|589c2c4|M2-02 查询向量、精确余弦 SQL 与预过滤|
|b4e5584|补齐 VectorSearchIT 默认发现|
|458b83b|M2-03 ES BM25、terms 过滤及 PG 批量补全|
|3fbc4c6|terms 超限类型与设置回退修复|
|36efb5f|M2-04 RRF、去重、证据一致性|
|a1c0c72|M2-05 复用 Reranker、业务 ID 映射|
|6f43713|职责分包、XML namespace 与引用同步|
|8305c4c|重排边界独立测试补充|
|2795e7a|M2-06 编排、API、诊断及集成名单|
|e907b05|M2-07 阶段证据及未完成项如实记录|
|63f24c1|报告空白修正|
|ac7cb5b|快照构造损坏映射 SNAPSHOT_INVALID|
|5e814b4|多 Chunk、故障恢复、脱敏与诊断验收收尾|

逐提交文件列表与历史 patch 已扫描，不仅检查 HEAD。未见无关功能、生产临时调试输出、生成产物、冻结 Spec 或既有 Flyway 修改；首次发布事务未修改。pom 只增加四个 Failsafe includes，无依赖升级或新增运行时依赖。

## 58 文件分类

7 份 M2 工程报告、1 个 pom、4 个 M1 上传 Java 文件、30 个 retrieval Java 文件、4 个 Mapper XML（含容量锁）、12 个测试文件，共 58。具体路径可由 `git diff --name-only origin/main...5e814b4` 重现。未涉及 Memory、Agent、Web UI 或 M3 实现。

## 核心生产代码

- snapshot：单条 LEFT JOIN SELECT 保留损坏发布指针；校验 KB、版本存在和文档归属。不可变快照含确定 hash。Repository 仅转换构造校验异常，读取异常分类独立。Service 请求入口只读一次，无静默刷新，无跨模型/ES 网络事务。
- vector/embedding：`VectorSearchMapper.xml` 使用 `<=>` 真实余弦距离；无近似索引变更。KB、成对 Document/Version、active 指针及归属均在 ORDER BY/LIMIT 前约束，distance/chunkId 稳定排序。查询向量校验维度、有限非零和客户端模型配置；单次生成供编排复用。
- keyword：真实 ES match/BM25，复用既有 IK Mapping 验证；KB/版本 terms 前置。读取实际 index.max_terms_count 和 defaults，与应用 2000 上限取小值，超限拒绝。禁 partial、检查 timeout/shard；候选协议、ID 和归属校验后一次 PG 批量补全正文和来源，不一致整路失败。同分为 keyword chunk_id 字典序，是已记录决策。
- fusion：按 chunkId 去重，相同 ID 全证据一致性校验；RRF 为排名贡献之和，原始 rank/distance/score 保留；按分数降序、数值 ID 升序，使用 fusionTopK。
- rerank：复用 M0 Reranker，完整融合池一次调用、topN=N，十进制业务 ID 映射。数量、已知唯一完整 ID、有限 [0,1] 分数均验证；稳定排序保留融合顺序。失败安全包装，无部分结果或额外模型调用。
- RetrievalService：按需共享一次快照/Embedding/VECTOR/BM25/RRF/Rerank；独立策略不被另一通道故障拖累，混合策略禁止部分成功及隐式降级。三态明确；finalTopK 最后执行，finalRank 连续，诊断保留完整阶段池。
- API：参数完整提供或整体默认 20/20/60/20/5；正数、上限和策略池关系校验，重复策略拒绝（不静默重复执行）。既有 Long 字符串、Result、RequestId、404 协议复用。合法请求策略故障为 HTTP 200 Envelope 内 FAILED，未暴露原异常或向量；成功证据正文合法返回。

可读性：主流程中文注释和职责分层清楚，编排虽较长但已按阶段拆分；未发现足以要求阻断性重构的复杂度。测试复用少量包内辅助断言及既有容器基类，存在维护依赖但未发现错误耦合。

## M1 上传回归

五个文件均来自 e075598。1000 依据 configuration-baseline.md 的既有工程初始值，并非性能容量承诺；所有 document 状态计数，当前无删除。增加容量控制防止无界发布集合；不能用它替代 ES terms 实际上限检查。

knowledge_base 行锁串行同 KB 创建，在 READ COMMITTED 下锁后重新去重、计数再创建，避免并发超过最后名额；不同 KB 独立。重复内容优先容量拒绝。锁仅在短 PG 创建事务中；MinIO 上传、复制、删除仍在外部。锁顺序没有向首次发布引入反向 KB 锁。原任务创建、唯一约束、重复返回及单 Worker 协议保留。

提交结果未知时仍先查主库引用；被引用或无法确认引用时保留正式对象。锁内重复的本请求多余对象按既有引用保护规则补偿。该行为变化有业务依据，应保留。

RetrievalFoundationIT 真实 PG 验证 999+两个竞争上传只有一个成功、最终 1000、重复优先、不同 KB、回滚和对象补偿；对象存储为内存 Fake。StorageImportIT 使用真实 PG/MinIO 回归复制失败、事务失败、竞争去重、提交响应丢失及引用查询不可用保护；并非真实 MinIO 下的满容量竞争专项。首次发布及完整单 Worker 链路由默认既有 IT 回归。当前代码审查未见新增 M1 阻断风险。

## M2-07 证据核查

报告按历史 PARTIAL 与最终收尾分段，不能将前段未完成项误读为当前状态。RetrievalServiceIT 实际构造 8 个 Chunk：KB601 发布 604/607/610/615，未发布616、历史617；KB611 发布614/618。非法候选含完全同向向量和高密度 body，真实 SQL/ES 在 Top-K 前过滤；KB2 的两条独立召回可用。

阶段完整排序为 VECTOR 604→607→610→615，BM25 610→604→607→615，RRF 604→610→607→615，重排615→604→610→607；最终取2。Fake 对615给.99、其余.1且检查4项/topN4，同分按融合顺序。VECTOR 完整顺序、Rerank 首项提升、RRF 公式和原始证据、来源、重复执行、阶段数与分数对应均有断言；其余完整顺序通过本轮 M2_MULTI 测试输出核对，不声称全部有 containsExactly 断言；不将固定分数视为真实质量评估。

PG/ES 使用真实 Testcontainers pause/unpause，finally 恢复，JUnit 生命周期清理，非 Mockito 或 Compose。PG 故障为快照读失败且无下游阶段；ES 故障保持 VECTOR 成功，其余失败；恢复后四策略成功。日志/API sentinel 测试检查 query、正文、向量、密钥、原异常不外泄；正常 API 正文另有合法返回断言。失败 chunks 为空，已成功阶段的安全 ID/分数诊断可保留。

## 构建与发现边界

实际执行默认 `mvn clean verify`，无 online-model、includes 覆盖、skipTests 或 skipITs。BUILD SUCCESS，退出码0，耗时11:53，2026-11-11 10:39:16 +08:00 完成。逐份解析本轮 target/surefire-reports/TEST-*.xml：42类、236项；target/failsafe-reports/TEST-*.xml：43项；合计279，失败/错误/跳过均0，与历史基线一致。RetrievalServiceIT 2项（含继承ES基线）通过，M2_MULTI 输出四阶段完整排序、分数及 hash 与报告一致。git diff --check 与 git diff --cached --check 通过。

Surefire 显式 *Tests；Failsafe 显式16类，包含全部四个新增 M2 IT。在线 ModelOnlineIT/RerankOnlineIT 仅显式 profile；EmbeddingBaselineOnlineIT 需另行显式授权执行。既有 ChunkPersistenceIT、EmbeddingPersistenceIT 不在默认名单，MarkdownPerformanceIT 为显式性能实验；本轮默认构建不声称运行这些额外测试，也未修改名单。这是全量一词的实际发现范围限制，核心完整导入回归另有 IngestionPipelineIT。

测试使用隔离容器和临时索引，固定 pgvector0.8.0/PG17 digest（实际17.6）、本地 ES8.18.0/IK8.18.0、MinIO RELEASE.2025-09-07T16-13-09Z-local。镜像需预置、Docker 可用；未启动开发 Compose 或使用其卷。未请求真实供应商。

## 安全与文档

历史 patch 扫描私钥标记、常见密钥格式、password/api key、调试输出，并核对匹配上下文；未发现新增真实凭证，测试凭证/哨兵不等于泄漏。SQL 参数绑定，无业务输入拼 SQL。此审查不是所有第三方日志或全仓秘密检测工具的保证。

M2-01～07 七份报告齐全。M2-06 含 API、完整参数、默认值、失败含义；模型与 ES 启用需结合 application 配置及 local-development 使用：assistant.elasticsearch.enabled、assistant.model.enabled、assistant.rerank.enabled 默认关闭，模型配置与凭证必须显式配置。Docker/镜像和固定数据证据已记录。不重写 README。

文档已知差异：用户指引称 Architecture 为 Boot3.x，实际当前 architecture.md 与 pom 均4.1.2，M2-01 已记录；不修改冻结文档。Architecture 概念 API 单策略与已确认 M2-06 多策略数组存在已记录差异。早期报告能力状态按对应里程碑阅读，最终以 M2-07 收尾及本轮执行结果为准。

## 问题与风险分级

P0：未发现。P1 阻断缺陷：未发现。P2 阻断缺陷：未发现。无需修改生产代码。

非阻断验证待办：pom.xml 默认名单未包含既有 ChunkPersistenceIT/EmbeddingPersistenceIT；触发条件为仅依赖默认 verify 作所有测试覆盖声明，影响是这两类专项不执行。建议后续构建维护任务评估纳入离线名单或明确专项执行口径；本轮保持用户要求的默认配置，并如实记录，不能声称其通过。

可接受 V1 边界：仅首次发布，不支持在线版本切换；不保证切换期间旧快照持续可读；无生产级高可用保证。版本租约/切换为明确 P1，不阻断 M2。

M3/M4/M5 验证：真实 Embedding 语义质量、在线 Rerank 效果、问答引用、固定数据集指标、大规模性能/QPS、生产故障与部署文档。固定向量和 Fake Rerank 均不能证明真实语义效果；当前无大规模性能基线。不得将这些延期能力误判为本轮已验证。

## 最终结论

READY TO MERGE。未发现需要先修复的阻断缺陷；M1 回归风险在当前 V1 边界内可接受。仅提交本报告，不执行合并、推送或历史改写。本报告提交 SHA 见 Git 与任务最终回复，避免自引用。
